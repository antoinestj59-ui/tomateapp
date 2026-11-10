package tomate.app.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import tomate.core.model.Outcome
import tomate.data.container.TomateContainer
import tomate.data.extras.CallCodec
import tomate.data.extras.CallSignal

/**
 * Relie le moteur d'appel à la conversation : la signalisation passe par des messages ordinaires,
 * découpés par [CallCodec], et la conversation est relue toutes les deux secondes pendant que
 * l'écran est ouvert — sans cela un appel entrant ne sonnerait jamais, comme sur le web.
 *
 * Les signaux déjà traités sont mémorisés : relire la conversation ne rejoue ni une offre ni un
 * candidat ICE, ce qui éviterait un second appel ou une réponse en double.
 */
class CallController(
    private val container: TomateContainer,
    private val conversationId: String,
    private val scope: CoroutineScope,
) {
    private val meId: String? get() = container.session.currentUserId
    private val seen = HashSet<String>()
    private val startedAt = System.currentTimeMillis()

    @Volatile
    private var ice: List<PeerConnection.IceServer> = CallEngine.iceServers(null, null, null)

    val engine = CallEngine(container.applicationContext, scope) { ice }

    val state: StateFlow<CallEngine.Snapshot> get() = engine.state

    init {
        engine.transport = { signal -> send(signal) }

        // Le relais TURN enregistré sur l'appareil, relu à chaque changement de réglages.
        scope.launch {
            container.settings.settings.collect { settings ->
                ice = CallEngine.iceServers(settings.turnUrl, settings.turnUser, settings.turnPassword)
            }
        }

        // Tout message de la conversation peut contenir un morceau de signalisation.
        scope.launch {
            container.messaging.observeMessages(conversationId).collect { messages ->
                consume(messages)
            }
        }

        // Relance la lecture distante : c'est ce qui fait sonner un appel entrant.
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val stage = engine.state.value.stage
                if (stage == CallEngine.Stage.Idle || stage == CallEngine.Stage.Incoming) {
                    runCatching { container.messaging.refreshMessages(conversationId) }
                }
            }
        }
    }

    fun call(video: Boolean) = engine.call(video)

    fun accept() = engine.accept()

    fun decline() = engine.decline()

    fun hangUp() = engine.hangUp()

    fun setMuted(muted: Boolean) = engine.setMuted(muted)

    fun setCameraOff(off: Boolean) = engine.setCameraOff(off)

    fun setSpeaker(on: Boolean) = engine.setSpeaker(on)

    fun clear() = engine.clear()

    fun release() = engine.release()

    private fun consume(messages: List<tomate.core.model.Message>) {
        val signals = CallCodec.collect(messages, meId)
        for (signal in signals) {
            val key = key(signal)
            if (!seen.add(key)) continue
            // Une offre antérieure à l'ouverture de l'écran ne doit pas sonner rétroactivement.
            if (signal.isOffer && (signal.at ?: 0L) < startedAt - STALE_MS) continue
            engine.handle(signal)
        }
    }

    private suspend fun send(signal: CallSignal) {
        for (chunk in CallCodec.pack(signal)) {
            // `updatePreview = false` : la conversation ne doit pas afficher du protocole.
            val result = container.messaging.send(conversationId, chunk, updatePreview = false)
            if (result is Outcome.Failure && !result.error.isOffline) return
        }
    }

    private fun key(signal: CallSignal): String = buildString {
        append(signal.callId).append('|').append(signal.kind).append('|').append(signal.at)
        append('|').append(signal.sdp?.hashCode() ?: 0)
        signal.candidate?.let { append('|').append(it.toString().hashCode()) }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val STALE_MS = 15_000L
    }
}
