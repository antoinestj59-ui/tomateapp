package tomate.app.call

import android.content.Context
import android.media.AudioManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import tomate.data.extras.CallCodec
import tomate.data.extras.CallSignal

/**
 * Appels audio/vidéo 1-à-1, côté Android.
 *
 * Le moteur ne connaît ni Supabase ni Compose : il reçoit des [CallSignal] déjà réassemblés et
 * les émet par le `transport` fourni. C'est le même découpage que `CallSession` côté web, ce qui
 * permet de répondre à un appel lancé depuis le navigateur (et inversement).
 *
 * Sans appareil photo ou sans permission, la vidéo est abandonnée et l'appel reste audio : le
 * moteur le dit dans `label` au lieu d'échouer silencieusement.
 */
class CallEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val iceServers: () -> List<PeerConnection.IceServer>,
) {
    enum class Stage { Idle, Incoming, Dialing, Connected, Ended }

    data class Snapshot(
        val stage: Stage = Stage.Idle,
        val callId: String? = null,
        val video: Boolean = false,
        val label: String = "",
        val micMuted: Boolean = false,
        val cameraOff: Boolean = false,
        val remoteVideo: VideoTrack? = null,
        val localVideo: VideoTrack? = null,
        val iceState: String = "",
        val failure: String? = null,
    ) {
        val ringing: Boolean get() = stage == Stage.Incoming
        val active: Boolean get() = stage == Stage.Dialing || stage == Stage.Connected
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Émission d'un signal vers la conversation. Renseigné par le contrôleur. */
    var transport: (suspend (CallSignal) -> Unit)? = null

    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var pendingOfferSdp: String? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var speakerOn = true

    /** L'EGL partagé par les rendus locaux et distants. */
    val eglBase: EglBase by lazy { EglBase.create() }

    private fun factory(context: Context): PeerConnectionFactory = Rtc.factory(context, eglBase)

    // ------------------------------------------------------------------ actions

    fun call(video: Boolean) {
        if (_state.value.stage != Stage.Idle) return
        val callId = UUID.randomUUID().toString()
        _state.value = Snapshot(stage = Stage.Dialing, callId = callId, video = video, label = "Connexion…")
        val prepared = runCatching {
            openPeer(callId, video)
            val constraints = MediaConstraints()
            peer?.createOffer(
                object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        peer?.setLocalDescription(
                            object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    emit(
                                        CallSignal(
                                            kind = "offer",
                                            callId = callId,
                                            video = video,
                                            sdp = description.description,
                                        ),
                                    )
                                }

                                override fun onSetFailure(error: String?) = fail("Offre refusée : $error")
                            },
                            description,
                        )
                    }

                    override fun onCreateFailure(error: String?) = fail("Offre impossible : $error")
                },
                constraints,
            )
        }
        prepared.exceptionOrNull()?.let { fail("Appel impossible : ${it.message ?: "micro indisponible"}") }
    }

    /** Accepte l'appel entrant ; la réponse SDP part automatiquement. */
    fun accept() {
        val snapshot = _state.value
        val sdp = pendingOfferSdp ?: return
        if (snapshot.stage != Stage.Incoming) return
        val callId = snapshot.callId ?: return
        _state.value = snapshot.copy(stage = Stage.Dialing, label = "Réponse…")
        runCatching {
            openPeer(callId, snapshot.video)
            peer?.setRemoteDescription(
                object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        flushRemoteCandidates()
                        peer?.createAnswer(
                            object : SimpleSdpObserver() {
                                override fun onCreateSuccess(description: SessionDescription) {
                                    peer?.setLocalDescription(
                                        object : SimpleSdpObserver() {
                                            override fun onSetSuccess() {
                                                emit(
                                                    CallSignal(
                                                        kind = "answer",
                                                        callId = callId,
                                                        sdp = description.description,
                                                    ),
                                                )
                                                _state.value = _state.value.copy(label = "Connexion…")
                                            }

                                            override fun onSetFailure(error: String?) =
                                                fail("Réponse refusée : $error")
                                        },
                                        description,
                                    )
                                }

                                override fun onCreateFailure(error: String?) =
                                    fail("Réponse impossible : $error")
                            },
                            MediaConstraints(),
                        )
                    }

                    override fun onSetFailure(error: String?) = fail("Offre illisible : $error")
                },
                SessionDescription(SessionDescription.Type.OFFER, sdp),
            )
        }.exceptionOrNull()?.let { fail("Appel impossible : ${it.message ?: "micro indisponible"}") }
    }

    fun decline() {
        val callId = _state.value.callId
        if (callId != null) emit(CallSignal(kind = "end", callId = callId))
        close()
        _state.value = Snapshot(stage = Stage.Ended, label = "Appel refusé")
    }

    fun hangUp(reason: String = "Appel terminé") {
        val callId = _state.value.callId
        if (callId != null) emit(CallSignal(kind = "end", callId = callId))
        close()
        if (_state.value.stage != Stage.Idle) {
            _state.value = Snapshot(stage = Stage.Ended, label = reason)
        }
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
        _state.value = _state.value.copy(micMuted = muted)
    }

    fun setCameraOff(off: Boolean) {
        videoTrack?.setEnabled(!off)
        _state.value = _state.value.copy(cameraOff = off)
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        applyAudioRoute()
    }

    val isSpeakerOn: Boolean get() = speakerOn

    /** Réinitialise après un message « terminé » affiché. */
    fun clear() {
        if (_state.value.stage == Stage.Ended) _state.value = Snapshot()
    }

    // ------------------------------------------------------------- signalisation

    fun handle(signal: CallSignal) {
        when {
            signal.isOffer -> {
                if (_state.value.stage != Stage.Idle) return
                pendingOfferSdp = signal.sdp
                if (signal.sdp == null) return
                pendingRemoteCandidates.clear()
                _state.value = Snapshot(
                    stage = Stage.Incoming,
                    callId = signal.callId,
                    video = signal.video == true,
                    label = if (signal.video == true) "Appel vidéo entrant" else "Appel audio entrant",
                )
            }

            signal.isAnswer -> {
                val sdp = signal.sdp ?: return
                if (_state.value.callId != signal.callId) return
                peer?.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            flushRemoteCandidates()
                            _state.value = _state.value.copy(label = "Connexion…")
                        }

                        override fun onSetFailure(error: String?) = fail("Réponse illisible : $error")
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp),
                )
            }

            signal.isIce -> {
                if (_state.value.callId != signal.callId) return
                val (mid, line, sdp) = signal.iceParts() ?: return
                val candidate = IceCandidate(mid, line ?: 0, sdp)
                val connection = peer
                if (connection?.remoteDescription != null) {
                    connection.addIceCandidate(candidate)
                } else {
                    pendingRemoteCandidates += candidate
                }
            }

            signal.isEnd -> {
                if (_state.value.callId != signal.callId) return
                close()
                _state.value = Snapshot(stage = Stage.Ended, label = "Appel terminé")
            }
        }
    }

    // ------------------------------------------------------------------ moteur

    private fun openPeer(callId: String, video: Boolean) {
        if (peer != null) return
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val connection = factory(appContext).createPeerConnection(config, PeerObserver()) ?: error("peer indisponible")
        peer = connection

        val audio = factory(appContext).createAudioSource(MediaConstraints())
        audioSource = audio
        val track = factory(appContext).createAudioTrack("tomate-audio", audio)
        audioTrack = track
        connection.addTrack(track, listOf(STREAM_ID))

        if (video) {
            val added = runCatching { startCamera() }.getOrNull()
            if (added != null) connection.addTrack(added, listOf(STREAM_ID))
            else _state.value = _state.value.copy(label = "Connexion… (vidéo indisponible, audio seul)")
        }

        applyAudioRoute()
        _state.value = _state.value.copy(callId = callId)
    }

    private fun startCamera(): VideoTrack? {
        val videoSource = factory(appContext).createVideoSource(false)
        this.videoSource = videoSource
        val helper = SurfaceTextureHelper.create("tomate-capture", eglBase.eglBaseContext)
        surfaceHelper = helper
        val enumerator = Camera2Enumerator(appContext)
        val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        val capturer = enumerator.createCapturer(device, null) ?: return null
        this.capturer = capturer
        capturer.initialize(helper, appContext, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        val track = factory(appContext).createVideoTrack("tomate-video", videoSource)
        videoTrack = track
        _state.value = _state.value.copy(localVideo = track, video = true)
        return track
    }

    private fun applyAudioRoute() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speakerOn
        }
    }

    private fun flushRemoteCandidates() {
        val connection = peer ?: return
        pendingRemoteCandidates.forEach { connection.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun emit(signal: CallSignal) {
        val send = transport ?: return
        scope.launch(Dispatchers.IO) { runCatching { send(signal) } }
    }

    private fun fail(message: String) {
        close()
        _state.value = Snapshot(stage = Stage.Ended, label = message, failure = message)
    }

    private fun close() {
        pendingOfferSdp = null
        pendingRemoteCandidates.clear()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        capturer = null
        surfaceHelper = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peer?.close() }
        peer = null
        runCatching {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        if (_state.value.stage != Stage.Ended) _state.value = Snapshot()
    }

    /** Libère tout : appelé quand l'écran de conversation disparaît. */
    fun release() {
        hangUp("Appel terminé")
        _state.value = Snapshot()
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val callId = _state.value.callId ?: return
            emit(
                CallSignal(
                    kind = "ice",
                    callId = callId,
                    candidate = CallCodec.candidate(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    ),
                ),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            _state.value = _state.value.copy(iceState = state.name.lowercase())
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> _state.value = _state.value.copy(stage = Stage.Connected, label = "En communication")

                PeerConnection.IceConnectionState.DISCONNECTED ->
                    _state.value = _state.value.copy(label = "Connexion interrompue…")

                PeerConnection.IceConnectionState.FAILED ->
                    fail("Appel échoué : la liaison directe n'a pas abouti (réseau restrictif ou NAT).")

                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let { attachRemoteVideo(it) }
        }

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) attachRemoteVideo(track)
            if (track is AudioTrack) track.setEnabled(true)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) attachRemoteVideo(track)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    _state.value = _state.value.copy(stage = Stage.Connected, label = "En communication")

                PeerConnection.PeerConnectionState.FAILED ->
                    fail("Appel échoué : la liaison directe n'a pas abouti (réseau restrictif ou NAT).")

                else -> Unit
            }
        }
    }

    private fun attachRemoteVideo(track: VideoTrack) {
        _state.value = _state.value.copy(remoteVideo = track, video = true)
    }

    /** SDP observer minimal : seules les erreurs et les succès utiles sont surchargés. */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    /** Initialisation unique et coûteuse : une seule factory pour tout le processus. */
    private object Rtc {
        @Volatile private var instance: PeerConnectionFactory? = null

        fun factory(context: Context, eglBase: EglBase): PeerConnectionFactory {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                val audio = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
                val created = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audio)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .createPeerConnectionFactory()
                instance = created
                return created
            }
        }
    }

    companion object {
        private const val STREAM_ID = "tomate-stream"

        /** Même STUN que la PWA quand aucun relais n'est configuré. */
        val DEFAULT_ICE: List<String> = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
        )

        fun iceServers(turnUrl: String?, username: String?, password: String?): List<PeerConnection.IceServer> {
            val servers = DEFAULT_ICE.map { PeerConnection.IceServer.builder(it).createIceServer() }
            if (turnUrl.isNullOrBlank() || !turnUrl.startsWith("turn")) return servers
            val builder = PeerConnection.IceServer.builder(turnUrl)
            if (!username.isNullOrBlank()) builder.setUsername(username)
            if (!password.isNullOrBlank()) builder.setPassword(password)
            return servers + builder.createIceServer()
        }
    }
}
