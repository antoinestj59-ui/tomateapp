package tomate.app.notifications

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tomate.app.MainActivity
import tomate.app.R
import tomate.app.TomateApplication

/**
 * Bridges Firebase Cloud Messaging and TOMATE.
 *
 * Everything here is defensive: a build without `app/google-services.json` has no Firebase
 * configuration, and Firebase throws on first use. That is expected — the rest of the app keeps
 * working, notifications simply stay in-app (see the notification centre). The reason is never
 * hidden from the user: the settings screen says push is not configured.
 */
object PushRegistrar {

    const val CHANNEL_ID = "tomate_messages"
    private const val CHANNEL_NAME = "Messages et activité"

    fun ensureChannel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(CHANNEL_NAME)
            .setDescription("Messages, réactions et demandes d'adhésion")
            .build()
        manager.createNotificationChannel(channel)
    }

    /** Asks Firebase for this device token and stores it for the current account. */
    fun requestToken(context: Context) {
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> register(context, token) }
                .addOnFailureListener { /* Firebase not configured: in-app notifications only. */ }
        } catch (_: Throwable) {
            // No google-services.json / no FirebaseApp: nothing to register.
        }
    }

    fun register(context: Context, token: String) {
        if (token.isBlank()) return
        val app = context.applicationContext as? TomateApplication ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { app.container.push.registerToken(token) }
        }
    }

    fun show(context: Context, title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val intent = android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify((title + body).hashCode(), notification) }
    }
}
