package tomate.data.backend

/**
 * Public Supabase configuration.
 *
 * These two values are *public by design*: they only identify the project and are bounded by
 * Row Level Security. They are shipped inside the APK, exactly like the anon/publishable key of
 * any Supabase client. A `service_role` / secret key must NEVER appear here or anywhere in the
 * repository or the APK.
 *
 * They can be overridden at build time for a staging project with:
 *   ./gradlew -Ptomate.supabaseUrl=... -Ptomate.supabasePublishableKey=...
 * see tooling/bootstrap-android.sh and docs/05_SUPABASE_SETUP.md.
 */
object SupabaseConfig {
    const val DEFAULT_URL = "https://omsupwmlncuapikbjumg.supabase.co"
    const val DEFAULT_PUBLISHABLE_KEY = "sb_publishable_IOga6ANcbYMivphwt4mR7Q_VziRyrIj"

    /** Set from Gradle properties if present, otherwise the checked-in defaults. */
    var url: String = DEFAULT_URL
        private set

    var publishableKey: String = DEFAULT_PUBLISHABLE_KEY
        private set

    fun override(url: String, publishableKey: String) {
        if (url.isNotBlank() && publishableKey.isNotBlank()) {
            this.url = url.trim().trimEnd('/')
            this.publishableKey = publishableKey.trim()
        }
    }

    /** Never log this object: the publishable key is public but there is no reason to log it. */
    override fun toString(): String = "SupabaseConfig(url=$url, key=***)"
}
