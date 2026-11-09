package tomate.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives FCM pushes sent by the `push` Edge Function. */
class TomateMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.register(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "TOMATE"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Nouvelle activité sur TOMATE"
        PushRegistrar.show(applicationContext, title, body)
    }
}
