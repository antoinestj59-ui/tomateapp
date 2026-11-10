package tomate.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import tomate.app.call.CallEngine

/**
 * Fenêtre d'appel : sonnerie, communication, échec.
 *
 * Les vidéos passent par deux [SurfaceViewRenderer] : le flux distant en grand, l'aperçu local en
 * incrustation. Quand la vidéo n'est pas disponible, la fenêtre reste utilisable en audio et
 * l'état affiché le dit.
 */
@Composable
fun CallOverlay(
    snapshot: CallEngine.Snapshot,
    eglBase: EglBase,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (snapshot.stage) {
        CallEngine.Stage.Idle -> Unit

        CallEngine.Stage.Incoming -> AlertDialog(
            onDismissRequest = onDecline,
            title = { Text(snapshot.label.ifBlank { "Appel entrant" }) },
            text = {
                Text(
                    if (snapshot.video) "L'appel demande la caméra : la vidéo ne s'active qu'une fois acceptée."
                    else "L'appel utilise le micro de cet appareil.",
                )
            },
            confirmButton = { TextButton(onClick = onAccept) { Text("Répondre") } },
            dismissButton = { TextButton(onClick = onDecline) { Text("Refuser") } },
        )

        CallEngine.Stage.Dialing, CallEngine.Stage.Connected -> Dialog(
            onDismissRequest = { },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101010)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (snapshot.remoteVideo != null) {
                        RtcVideo(
                            track = snapshot.remoteVideo,
                            eglBase = eglBase,
                            mirror = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = if (snapshot.video) Icons.Default.Videocam else Icons.Default.Call,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                    if (snapshot.localVideo != null && !snapshot.cameraOff) {
                        RtcVideo(
                            track = snapshot.localVideo,
                            eglBase = eglBase,
                            mirror = true,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(width = 110.dp, height = 150.dp)
                                .background(Color.Black, RoundedCornerShape(12.dp)),
                        )
                    }
                    Text(
                        text = snapshot.label.ifBlank { "Connexion…" },
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallAction(
                        icon = if (snapshot.micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (snapshot.micMuted) "Micro coupé" else "Micro",
                        onClick = onToggleMute,
                    )
                    CallAction(
                        icon = Icons.Default.VolumeUp,
                        label = "Haut-parleur",
                        onClick = onToggleSpeaker,
                    )
                    if (snapshot.video) {
                        CallAction(
                            icon = if (snapshot.cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            label = if (snapshot.cameraOff) "Caméra coupée" else "Caméra",
                            onClick = onToggleCamera,
                        )
                    }
                    CallAction(
                        icon = Icons.Default.CallEnd,
                        label = "Raccrocher",
                        tint = Color(0xFFD32F2F),
                        onClick = onHangUp,
                    )
                }
            }
        }

        CallEngine.Stage.Ended -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (snapshot.failure != null) "Appel impossible" else "Appel terminé") },
            text = { Text(snapshot.label.ifBlank { "L'appel est terminé." }) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        )
    }
}

@Composable
private fun CallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(64.dp)
                .background(Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Affiche un flux vidéo WebRTC : le renderer est libéré quand il quitte l'écran. */
@Composable
private fun RtcVideo(track: VideoTrack, eglBase: EglBase, mirror: Boolean, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var initialized by remember { mutableStateOf(false) }

    DisposableEffect(track, initialized) {
        if (initialized) track.addSink(renderer)
        onDispose { runCatching { track.removeSink(renderer) } }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                renderer = this
                initialized = true
            }
        },
        onRelease = { view ->
            initialized = false
            runCatching { track.removeSink(view) }
            runCatching { view.release() }
        },
    )
}
