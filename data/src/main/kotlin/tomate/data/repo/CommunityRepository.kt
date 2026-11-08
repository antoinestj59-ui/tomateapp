package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.AppError
import tomate.core.model.Community
import tomate.core.model.CommunityRole
import tomate.core.model.MembershipStatus
import tomate.core.model.Outcome
import tomate.core.model.Visibility
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.CommunityDto
import tomate.data.backend.dto.CommunityMemberDto
import tomate.data.backend.dto.MembershipRowDto
import tomate.data.db.CommunityDao
import tomate.data.db.CommunityEntity
import tomate.data.db.CommunityMemberCacheEntity
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity
import tomate.data.mapper.toDomain
import tomate.data.mapper.toEntity

/**
 * Communities with public/private visibility and role-based moderation.
 * Membership is enforced server-side by RLS; the local cache only mirrors it for offline use.
 */
class CommunityRepository(
    private val api: SupabaseApi,
    private val communityDao: CommunityDao,
    private val outboxDao: OutboxDao,
    private val session: SessionManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeAll(): Flow<List<Community>> =
        communityDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeMine(): Flow<List<Community>> =
        communityDao.observeMine().map { rows -> rows.map { it.toDomain() } }

    fun observe(id: String): Flow<Community?> =
        communityDao.observeById(id).map { it?.toDomain() }

    suspend fun refresh(): Outcome<Int> {
        if (session.isLocalAccount()) return Outcome.Success(0)
        val userId = session.currentUserId
        return when (val response = api.select("communities", listOf("select" to "*", "order" to "name.asc"))) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(CommunityDto.serializer()), response.value)
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Communautés illisibles")) }

                val memberships = if (userId != null) fetchMemberships(userId) else emptyMap()
                val entities = rows.map { dto ->
                    val membership = memberships[dto.id]
                    dto.toEntity(
                        role = membership?.let { runCatching { CommunityRole.valueOf(it.role) }.getOrNull() },
                        membership = membership?.let { runCatching { MembershipStatus.valueOf(it.status) }.getOrNull() },
                    )
                }
                communityDao.upsertAll(entities)
                entities.forEach { entity ->
                    val membership = memberships[entity.id] ?: return@forEach
                    communityDao.upsertMember(
                        CommunityMemberCacheEntity(
                            communityId = entity.id,
                            userId = userId!!,
                            role = runCatching { CommunityRole.valueOf(membership.role) }.getOrDefault(CommunityRole.Member),
                            status = runCatching { MembershipStatus.valueOf(membership.status) }.getOrDefault(MembershipStatus.Pending),
                        ),
                    )
                }
                Outcome.Success(entities.size)
            }
        }
    }

    private suspend fun fetchMemberships(userId: String): Map<String, MembershipRowDto> {
        val response = api.select(
            "community_members",
            listOf("user_id" to "eq.$userId", "select" to "community_id,role,status"),
        )
        val text = (response as? Outcome.Success)?.value ?: return emptyMap()
        val rows = runCatching {
            SupabaseApi.json.decodeFromString(ListSerializer(MembershipRowDto.serializer()), text)
        }.getOrDefault(emptyList())
        return rows.associateBy { it.communityId }
    }

    suspend fun create(
        name: String,
        slugInput: String,
        description: String,
        visibility: Visibility,
    ): Outcome<Community> {
        val trimmedName = name.trim()
        if (trimmedName.length < 3) {
            return Outcome.Failure(AppError.Validation("short_name", "Le nom doit faire au moins 3 caractères."))
        }
        val slug = slugify(slugInput.ifBlank { trimmedName })
        if (slug.length < 3) {
            return Outcome.Failure(AppError.Validation("short_slug", "L'identifiant doit faire au moins 3 caractères."))
        }
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val id = UUID.randomUUID().toString()
        val dto = CommunityDto(
            id = id,
            name = trimmedName.take(60),
            slug = slug,
            description = description.trim().take(500),
            ownerId = userId,
            visibility = if (visibility == Visibility.Private) "Private" else "Public",
        )
        val payload = SupabaseApi.json.encodeToString(CommunityDto.serializer(), dto)
        if (SessionManager.isLocalId(userId)) {
            val entity = dto.toEntity(role = CommunityRole.Owner, membership = MembershipStatus.Active)
            communityDao.upsert(entity)
            communityDao.upsertMember(
                CommunityMemberCacheEntity(id, userId, CommunityRole.Owner, MembershipStatus.Active),
            )
            enqueue("community_create", id, payload)
            return Outcome.Success(entity.toDomain())
        }
        return when (val remote = api.insert("communities", payload)) {
            is Outcome.Success -> {
                val entity = dto.toEntity(role = CommunityRole.Owner, membership = MembershipStatus.Active)
                communityDao.upsert(entity)
                // The creator is inserted as owner by a database trigger; mirror it locally.
                communityDao.upsertMember(
                    CommunityMemberCacheEntity(id, userId, CommunityRole.Owner, MembershipStatus.Active),
                )
                Outcome.Success(entity.toDomain())
            }
            is Outcome.Failure -> {
                enqueue("community_create", id, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun join(communityId: String, visibility: Visibility): Outcome<Unit> {
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val status = if (visibility == Visibility.Private) MembershipStatus.Pending else MembershipStatus.Active
        val payload = SupabaseApi.json.encodeToString(
            CommunityMemberDto.serializer(),
            CommunityMemberDto(communityId, userId, role = "Member", status = status.name),
        )
        if (SessionManager.isLocalId(userId)) {
            communityDao.byId(communityId)?.let { existing ->
                communityDao.upsert(
                    existing.copy(
                        myRole = CommunityRole.Member,
                        myMembership = status,
                        memberCount = existing.memberCount + if (status == MembershipStatus.Active) 1 else 0,
                    ),
                )
            }
            communityDao.upsertMember(
                CommunityMemberCacheEntity(communityId, userId, CommunityRole.Member, status),
            )
            enqueue("community_join", "$communityId:$userId", payload)
            return Outcome.Success(Unit)
        }
        return when (val remote = api.insert("community_members", payload, upsert = true)) {
            is Outcome.Success -> {
                communityDao.byId(communityId)?.let { existing ->
                    communityDao.upsert(
                        existing.copy(
                            myRole = CommunityRole.Member,
                            myMembership = status,
                            memberCount = existing.memberCount + if (status == MembershipStatus.Active) 1 else 0,
                        ),
                    )
                }
                communityDao.upsertMember(
                    CommunityMemberCacheEntity(communityId, userId, CommunityRole.Member, status),
                )
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> {
                enqueue("community_join", "$communityId:$userId", payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    suspend fun leave(communityId: String): Outcome<Unit> {
        val userId = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val filter = listOf("community_id" to "eq.$communityId", "user_id" to "eq.$userId")
        return when (val remote = api.delete("community_members", filter)) {
            is Outcome.Success -> {
                communityDao.byId(communityId)?.let { existing ->
                    communityDao.upsert(
                        existing.copy(
                            myRole = null,
                            myMembership = null,
                            memberCount = (existing.memberCount - 1).coerceAtLeast(0),
                        ),
                    )
                }
                Outcome.Success(Unit)
            }
            is Outcome.Failure -> Outcome.Failure(remote.error)
        }
    }

    /** Moderator action: remove another member. Enforced by RLS (owner/moderator only). */
    suspend fun removeMember(communityId: String, memberId: String): Outcome<Unit> {
        val filter = listOf("community_id" to "eq.$communityId", "user_id" to "eq.$memberId")
        return when (val remote = api.delete("community_members", filter)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> Outcome.Failure(remote.error)
        }
    }

    private suspend fun enqueue(kind: String, key: String, payload: String) {
        val now = clock()
        outboxDao.enqueue(
            OutboxEntity(
                id = UUID.randomUUID().toString(),
                idempotencyKey = "$kind:$key",
                kind = kind,
                payload = payload,
                createdAt = now,
                attempts = 0,
                nextAttemptAt = now,
                lastError = null,
            ),
        )
    }

    companion object {
        fun slugify(input: String): String = input.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
    }
}
