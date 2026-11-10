package tomate.core.model

import kotlinx.serialization.Serializable

/**
 * Every repository call returns an [Outcome]: no exception escapes the data layer, so the UI
 * can always render an explicit loading / empty / error / offline state.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

/** Typed, user-facing error model. Messages stay in French for the first release. */
@Serializable
sealed class AppError {
    abstract val technicalMessage: String

    /** Short, actionable message shown in the UI. */
    abstract val userMessage: String

    /** True when the failure is caused by the absence of a usable Internet connection. */
    open val isOffline: Boolean get() = false

    /** True when retrying later could succeed without user action. */
    open val isRetryable: Boolean get() = false

    data class Offline(
        override val technicalMessage: String = "Réseau indisponible",
    ) : AppError() {
        override val userMessage: String = "Vous êtes hors ligne. L'action sera réessayée automatiquement."
        override val isOffline: Boolean get() = true
        override val isRetryable: Boolean get() = true
    }

    data class Unauthorized(
        override val technicalMessage: String = "Session absente ou expirée",
    ) : AppError() {
        override val userMessage: String = "Votre session a expiré. Reconnectez-vous."
        override val isRetryable: Boolean get() = true
    }

    data class NotFound(
        override val technicalMessage: String = "Ressource introuvable",
    ) : AppError() {
        override val userMessage: String = "Ce contenu n'existe plus ou n'est pas accessible."
    }

    data class Conflict(
        override val technicalMessage: String = "Conflit de données",
    ) : AppError() {
        override val userMessage: String =
            "Cette action existe déjà sur le serveur : elle a été ignorée, rien n'a été dupliqué."
    }

    /**
     * Refus explicite du serveur (politique RLS, 403). Ce n'est pas une session expirée : le
     * jeton est valide, mais cette écriture n'est pas autorisée pour ce compte.
     */
    data class Forbidden(
        override val technicalMessage: String = "Action non autorisée",
    ) : AppError() {
        override val userMessage: String =
            "Le serveur a refusé cette action : votre compte n'a pas les droits nécessaires."
        override val isRetryable: Boolean get() = true
    }

    data class Validation(
        override val technicalMessage: String,
        override val userMessage: String,
    ) : AppError()

    data class RateLimited(
        override val technicalMessage: String = "Trop de requêtes",
    ) : AppError() {
        override val userMessage: String = "Trop d'actions en peu de temps. Réessayez dans un moment."
        override val isRetryable: Boolean get() = true
    }

    data class Server(
        override val technicalMessage: String,
    ) : AppError() {
        override val userMessage: String = "Le serveur a rencontré un problème. Réessayez plus tard."
        override val isRetryable: Boolean get() = true
    }

    data class SchemaMissing(
        override val technicalMessage: String = "Schéma Supabase non initialisé",
    ) : AppError() {
        override val userMessage: String =
            "La base TOMATE n'est pas encore initialisée. Appliquez les migrations SQL documentées dans docs/05_SUPABASE_SETUP.md."
    }

    data class Unknown(
        override val technicalMessage: String,
    ) : AppError() {
        override val userMessage: String = "Une erreur inattendue est survenue."
    }
}
