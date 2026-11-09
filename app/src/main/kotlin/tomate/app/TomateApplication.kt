package tomate.app

import android.app.Application
import tomate.app.notifications.PushRegistrar
import tomate.data.container.TomateContainer

/**
 * Process-wide application object. It owns the dependency container; ViewModels never create
 * infrastructure themselves (no hidden database or HTTP client per screen).
 */
class TomateApplication : Application() {

    val container: TomateContainer by lazy { TomateContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PushRegistrar.ensureChannel(this)
        // No-op until app/google-services.json exists: see docs/10_PUSH_FIREBASE.md.
        PushRegistrar.requestToken(this)
    }

    companion object {
        lateinit var instance: TomateApplication
            private set
    }
}
