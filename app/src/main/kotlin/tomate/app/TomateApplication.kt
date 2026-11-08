package tomate.app

import android.app.Application
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
    }

    companion object {
        lateinit var instance: TomateApplication
            private set
    }
}
