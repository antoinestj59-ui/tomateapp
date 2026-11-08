package tomate.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.core.model.Profile
import tomate.core.model.Visibility
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.HandleAvailability
import tomate.data.backend.dto.ProfileDto
import tomate.data.db.ProfileDao
import tomate.data.db.ProfileEntity
import tomate.data.mapper.toDomain
import tomate.data.mapper.toEntity

/**
 * Identity: anonymous Supabase account + local profile row.
 *
 * The account UUID (from Supabase Auth) is the permanent identity. The handle is only a
 * human-facing, mutable label; nothing security-related is ever derived from it.
 */
class ProfileRepository(
    private val api: SupabaseApi,
    private val profileDao: ProfileDao,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeMe(): Flow<Profile?> = profileDao.observeMe().map { it?.toDomain() }

    suspend fun currentUserId(): String? = session.currentUserId

    /** Pulls the remote profile for the current account, if any. */
    suspend fun pullMyProfile(): Outcome<Profile?> {
        val userId = session.ensureSession().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val response = api.select("profiles", listOf("id" to "eq.$userId", "select" to "*"))
        return when (response) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = decodeProfiles(response.value)
                val dto = rows.firstOrNull()
                if (dto == null) {
                    Outcome.Success(null)
                } else {
                    val entity = dto.toEntity(isMe = true)
                    profileDao.upsert(entity)
                    Outcome.Success(entity.toDomain())
                }
            }
        }
    }

    suspend fun isHandleAvailable(handle: String): Outcome<Boolean> {
        val normalised = normaliseHandle(handle)
        if (!isValidHandle(normalised)) {
            return Outcome.Success(false)
        }
        val myId = session.currentUserId
        val response = api.select(
            "profiles",
            listOf("handle" to "eq.$normalised", "select" to "id,handle"),
        )
        return when (response) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = decodeProfiles(response.value)
                val takenByOther = rows.any { it.id != myId }
                Outcome.Success(!takenByOther)
            }
        }
    }

    /**
     * Creates or updates the local + remote profile. The local write happens first so the user
     * keeps their identity even when the network fails; the remote write is retried by the
     * outbox (`profile_upsert`).
     */
    suspend fun saveProfile(
        handle: String,
        displayName: String,
        bio: String,
        visibility: Visibility,
    ): Outcome<Profile> {
        val normalised = normaliseHandle(handle)
        if (!isValidHandle(normalised)) {
            return Outcome.Failure(
                AppError.Validation(
                    technicalMessage = "invalid_handle",
                    userMessage = "Le pseudo doit contenir 3 à 20 caractères : lettres minuscules, chiffres ou _.",
                ),
            )
        }
        if (displayName.isBlank()) {
            return Outcome.Failure(
                AppError.Validation("empty_display_name", "Le nom affiché est obligatoire."),
            )
        }
        val userId = session.ensureSession().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())

        val now = clock()
        val entity = ProfileEntity(
            id = userId,
            handle = normalised,
            displayName = displayName.trim().take(40),
            bio = bio.trim().take(280),
            avatarUrl = null,
            visibility = visibility,
            createdAt = now,
            updatedAt = now,
            pendingSync = true,
            isMe = true,
        )
        profileDao.upsert(entity)

        val dto = ProfileDto(
            id = userId,
            handle = normalised,
            displayName = entity.displayName,
            bio = entity.bio,
            visibility = visibility.name,
        )
        val body = SupabaseApi.json.encodeToString(ProfileDto.serializer(), dto)
        val remote = api.insert("profiles", body, upsert = true, onConflict = "id")
        return when (remote) {
            is Outcome.Success -> {
                profileDao.markSynced(userId)
                Outcome.Success(entity.copy(pendingSync = false).toDomain())
            }
            is Outcome.Failure -> Outcome.Failure(remote.error)
        }
    }

    suspend fun pullProfile(userId: String): Outcome<Profile> {
        val response = api.select("profiles", listOf("id" to "eq.$userId", "select" to "*"))
        return when (response) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val dto = decodeProfiles(response.value).firstOrNull()
                    ?: return Outcome.Failure(AppError.NotFound("Profil introuvable"))
                val entity = dto.toEntity(isMe = dto.id == session.currentUserId)
                profileDao.upsert(entity)
                Outcome.Success(entity.toDomain())
            }
        }
    }

    fun search(query: String): Flow<List<Profile>> =
        profileDao.search(query.trim()).map { rows -> rows.map { it.toDomain() } }

    suspend fun markSynced() {
        session.currentUserId?.let { profileDao.markSynced(it) }
    }

    private fun decodeProfiles(text: String): List<ProfileDto> = runCatching {
        SupabaseApi.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ProfileDto.serializer()), text)
    }.getOrDefault(emptyList())

    companion object {
        private val HANDLE_REGEX = Regex("^[a-z0-9_]{3,20}$")
        private val RESERVED = setOf(
            "tomate", "admin", "support", "moderator", "moderation", "system", "root",
            "help", "about", "settings", "profil", "profile",
        )

        fun normaliseHandle(handle: String): String =
            handle.trim().lowercase().replace("@", "").replace(" ", "")

        fun isValidHandle(handle: String): Boolean =
            HANDLE_REGEX.matches(handle) && handle !in RESERVED

        fun availabilityReason(handle: String): String? = when {
            handle.length < 3 -> "Trop court (3 caractères minimum)."
            handle.length > 20 -> "Trop long (20 caractères maximum)."
            !HANDLE_REGEX.matches(handle) -> "Lettres minuscules, chiffres et _ uniquement."
            handle in RESERVED -> "Ce pseudo est réservé."
            else -> null
        }

        fun availability(available: Boolean, handle: String): HandleAvailability =
            HandleAvailability(available = available, reason = availabilityReason(handle))
    }
}
