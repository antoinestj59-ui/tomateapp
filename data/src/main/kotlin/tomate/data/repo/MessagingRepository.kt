package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.model.AppError
import tomate.core.model.ContactCandidate
import tomate.core.model.Conversation
import tomate.core.model.Message
import tomate.core.model.MessageDeliveryState
import tomate.core.model.Outcome
import tomate.core.model.TransportKind
import tomate.data.backend.SessionManager
import tomate.data.backend.SupabaseApi
import tomate.data.backend.dto.ConversationDto
import tomate.data.backend.dto.ConversationMemberDto
import tomate.data.backend.dto.MessageDto
import tomate.data.backend.dto.MessageInsert
import tomate.data.backend.dto.ProfileDto
import tomate.data.db.ConversationDao
import tomate.data.db.ConversationEntity
import tomate.data.db.ConversationMemberEntity
import tomate.data.db.MessageDao
import tomate.data.db.MessageEntity
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity
import tomate.data.db.ProfileDao
import tomate.data.db.StreakDayRow
import tomate.data.e2ee.E2eeEngine
import tomate.data.e2ee.E2eeStatus
import tomate.data.mapper.toDomain
import tomate.data.mapper.toEntity

/**
 * Messaging, local-first.
 *
 * A message is written to Room as `Pending`, then handed to the Internet transport. When the
 * network is unavailable it stays `Pending` and an idempotent outbox entry is scheduled, so a
 * lost connection never loses a message. Deduplication uses `client_id` (unique locally) and the
 * server primary key, and ordering uses `created_at` + client id as a stable tie-breaker.
 *
 * E2EE STATUS: [e2ee] is [E2eeNotConfigured] in this version; messages are therefore stored and
 * transmitted with transport security only, and the UI labels them "non chiffré de bout en bout".
 */
class MessagingRepository(
    private val api: SupabaseApi,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val profileDao: ProfileDao,
    private val outboxDao: OutboxDao,
    private val session: SessionManager,
    private val e2ee: E2eeEngine,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val encryptionStatus: E2eeStatus get() = e2ee.status

    /**
     * Conversations with their flame count. The streak is derived from the messages themselves:
     * a day counts only when both participants wrote, so no counter is ever invented.
     */
    fun observeConversations(): Flow<List<Conversation>> =
        combine(conversationDao.observeAll(), messageDao.observeStreakDays()) { rows, days ->
            val byConversation = days.groupBy { it.conversationId }
            rows.map { row ->
                row.toDomain().copy(streak = computeStreak(byConversation[row.id].orEmpty()))
            }
        }

    /** Consecutive days ending today (or yesterday, while today is still incomplete). */
    private fun computeStreak(days: List<StreakDayRow>): Int {
        val complete = days.filter { it.senders >= 2 }.map { it.dayIndex }.toSet()
        if (complete.isEmpty()) return 0
        val today = clock() / DAY_MS
        var cursor = if (today in complete) today else today - 1
        var count = 0
        while (cursor in complete) {
            count += 1
            cursor -= 1
        }
        return count
    }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeForConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    fun observePendingCount(): Flow<Int> = messageDao.observePendingCount()

    suspend fun refreshConversations(): Outcome<Int> {
        if (session.isLocalAccount()) return Outcome.Success(0)
        return when (val response = api.select("conversations", listOf("select" to "*", "order" to "last_message_at.desc"))) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(ConversationDto.serializer()), response.value)
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Conversations illisibles")) }
                rows.forEach { dto ->
                    val pending = messageDao.pendingCount(dto.id)
                    conversationDao.upsert(dto.toEntity(pending = pending))
                }
                Outcome.Success(rows.size)
            }
        }
    }

    suspend fun refreshMessages(conversationId: String): Outcome<Int> {
        if (session.isLocalAccount()) return Outcome.Success(0)
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        val query = listOf(
            "conversation_id" to "eq.$conversationId",
            "select" to "*",
            "order" to "created_at.asc",
            "limit" to "200",
        )
        return when (val response = api.select("messages", query)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(MessageDto.serializer()), response.value)
                }.getOrElse { return Outcome.Failure(AppError.Unknown("Messages illisibles")) }
                var inserted = 0
                rows.forEach { dto ->
                    // Deduplication: client_id is unique, so replays are ignored instead of duplicated.
                    val result = messageDao.insertIgnore(dto.toEntity(me))
                    if (result != -1L) inserted++
                }
                conversationDao.clearUnread(conversationId)
                Outcome.Success(inserted)
            }
        }
    }

    /** Creates a conversation locally and on the server. Group titles are optional. */
    suspend fun createConversation(
        title: String,
        memberHandles: List<String>,
        isGroup: Boolean = memberHandles.size > 1,
    ): Outcome<Conversation> {
        val me = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val id = UUID.randomUUID().toString()
        val resolvedTitle = when {
            isGroup -> title.trim().ifBlank { "Groupe" }
            else -> title.trim().ifBlank { "Conversation" }
        }
        val entity = ConversationEntity(
            id = id,
            title = resolvedTitle,
            isGroup = isGroup,
            lastMessagePreview = "",
            lastMessageAt = clock(),
            unreadCount = 0,
            pendingCount = 0,
        )
        conversationDao.upsert(entity)
        conversationDao.upsertMembers(
            (memberHandles + "me").map { handle ->
                ConversationMemberEntity(conversationId = id, memberId = handle, displayName = handle)
            } + ConversationMemberEntity(conversationId = id, memberId = me, displayName = "moi"),
        )

        val peers = memberHandles
            .map { it.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinct()
        val payload = SupabaseApi.json.encodeToString(
            ConversationDto.serializer(),
            ConversationDto(id = id, title = resolvedTitle, isGroup = isGroup, createdBy = me),
        )

        // A local account cannot resolve handles on the server: keep the conversation local and
        // queue the resolution so members are added when the account is linked.
        if (SessionManager.isLocalId(me)) {
            enqueue("conversation_create", id, payload)
            enqueue("conversation_member_add", id, memberPayload(id, me))
            peers.forEach { enqueue("conversation_member_add_handle", id, handlePayload(id, it)) }
            return Outcome.Success(entity.toDomain())
        }

        val resolved = resolveHandles(peers)
        return when (val remote = api.insert("conversations", payload, representation = false)) {
            is Outcome.Success -> {
                // Membership must be pushed explicitly: without it neither peer can read the
                // conversation, and the sender's own messages would be refused by RLS.
                api.insert("conversation_members", memberPayload(id, me), ignoreDuplicates = true, onConflict = "conversation_id,user_id", representation = false)
                resolved.values.forEach { peerId ->
                    api.insert(
                        "conversation_members",
                        memberPayload(id, peerId),
                        ignoreDuplicates = true,
                        onConflict = "conversation_id,user_id",
                        representation = false,
                    )
                }
                Outcome.Success(entity.toDomain())
            }
            is Outcome.Failure -> {
                enqueue("conversation_create", id, payload)
                enqueue("conversation_member_add", id, memberPayload(id, me))
                peers.forEach { peer ->
                    val peerId = resolved[peer]
                    if (peerId != null) enqueue("conversation_member_add", id, memberPayload(id, peerId))
                    else enqueue("conversation_member_add_handle", id, handlePayload(id, peer))
                }
                Outcome.Failure(remote.error)
            }
        }
    }

    /**
     * Contact suggestions for the "new conversation" picker.
     *
     * Only profiles actually returned by the server are listed: there is no fixture and no
     * invented person. A blank query lists the profiles the account may see; a non-blank query is
     * matched server-side on `handle` and `display_name`, so typing "an" finds @antoine.
     */
    suspend fun searchContacts(query: String, limit: Int = 20): Outcome<List<ContactCandidate>> {
        if (session.isLocalAccount()) return Outcome.Success(emptyList())
        val trimmed = query.trim().removePrefix("@").replace("*", "")
        val filters = buildList {
            add("select" to "id,handle,display_name")
            add("order" to "handle.asc")
            add("limit" to limit.toString())
            session.currentUserId?.let { add("id" to "neq.$it") }
            if (trimmed.isNotEmpty()) {
                val pattern = "*$trimmed*"
                add("or" to "(handle.ilike.$pattern,display_name.ilike.$pattern)")
            }
        }
        return when (val response = api.select("profiles", filters)) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val rows = runCatching {
                    SupabaseApi.json.decodeFromString(ListSerializer(ProfileDto.serializer()), response.value)
                }.getOrNull()
                if (rows == null) {
                    Outcome.Failure(AppError.Unknown("Contacts illisibles"))
                } else {
                    Outcome.Success(
                        rows.map { dto ->
                            ContactCandidate(
                                id = dto.id,
                                handle = dto.handle,
                                displayName = dto.displayName.ifBlank { dto.handle },
                            )
                        },
                    )
                }
            }
        }
    }

    /** Resolves handles to profile ids; unknown or unreachable handles are simply absent. */
    private suspend fun resolveHandles(handles: List<String>): Map<String, String> {
        if (handles.isEmpty()) return emptyMap()
        val filter = "in.(" + handles.joinToString(",") + ")"
        return when (val response = api.select("profiles", listOf("select" to "id,handle", "handle" to filter))) {
            is Outcome.Failure -> emptyMap()
            is Outcome.Success -> runCatching {
                SupabaseApi.json.decodeFromString(ListSerializer(ProfileDto.serializer()), response.value)
                    .associate { it.handle to it.id }
            }.getOrDefault(emptyMap())
        }
    }

    private fun memberPayload(conversationId: String, userId: String): String =
        """{"conversation_id":"$conversationId","user_id":"$userId"}"""

    private fun handlePayload(conversationId: String, handle: String): String =
        """{"conversation_id":"$conversationId","handle":"$handle"}"""

    /** Local-first send. Never loses the message: on failure it stays pending in the outbox. */
    suspend fun send(
        conversationId: String,
        body: String,
        replyToId: String? = null,
    ): Outcome<Message> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("empty_message", "Le message est vide."))
        }
        if (trimmed.length > MAX_MESSAGE_LENGTH) {
            return Outcome.Failure(
                AppError.Validation("message_too_long", "Maximum $MAX_MESSAGE_LENGTH caractères."),
            )
        }
        val me = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())

        val now = clock()
        val messageId = UUID.randomUUID().toString()
        val clientId = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = messageId,
            clientId = clientId,
            conversationId = conversationId,
            senderId = me,
            body = trimmed,
            createdAt = now,
            deliveryState = MessageDeliveryState.Pending,
            transport = null,
            encrypted = e2ee.status == E2eeStatus.Operational,
            replyToId = replyToId,
            attemptCount = 0,
            lastError = null,
        )
        messageDao.insert(entity)
        updateConversationSummary(conversationId, trimmed, now)

        val payload = SupabaseApi.json.encodeToString(
            MessageInsert.serializer(),
            MessageInsert(
                id = messageId,
                clientId = clientId,
                conversationId = conversationId,
                senderId = me,
                body = trimmed,
                encrypted = entity.encrypted,
                replyToId = replyToId,
            ),
        )
        if (SessionManager.isLocalId(me)) {
            // Stays Pending: the message exists locally and will leave through the outbox or a
            // nearby transport. Nothing is claimed as delivered.
            enqueue("message_send", clientId, payload)
            updatePendingCount(conversationId)
            return Outcome.Success(entity.toDomain())
        }
        return when (val remote = api.insert("messages", payload, upsert = true, onConflict = "id")) {
            is Outcome.Success -> {
                messageDao.updateDelivery(messageId, MessageDeliveryState.Delivered.name, TransportKind.Internet.name, null)
                updatePendingCount(conversationId)
                Outcome.Success(entity.copy(deliveryState = MessageDeliveryState.Delivered, transport = TransportKind.Internet).toDomain())
            }
            is Outcome.Failure -> {
                messageDao.recordAttempt(messageId, remote.error.technicalMessage)
                enqueue("message_send", clientId, payload)
                updatePendingCount(conversationId)
                Outcome.Failure(remote.error)
            }
        }
    }

    /**
     * Hands a locally created message to a nearby transport (BLE / Wi-Fi). The message leaves the
     * Internet path untouched: it is marked as sent to that transport and delivered when the peer
     * acknowledges it (see RelayEngine and the Proximity screen).
     */
    suspend fun markSentToTransport(messageId: String, transport: TransportKind): Outcome<Unit> {
        val message = messageDao.byId(messageId) ?: return Outcome.Failure(AppError.NotFound())
        messageDao.updateDelivery(
            messageId,
            MessageDeliveryState.SentToTransport.name,
            transport.name,
            null,
        )
        updatePendingCount(message.conversationId)
        return Outcome.Success(Unit)
    }

    suspend fun markDelivered(messageId: String) {
        val message = messageDao.byId(messageId) ?: return
        messageDao.updateDelivery(messageId, MessageDeliveryState.Delivered.name, message.transport?.name, null)
        updatePendingCount(message.conversationId)
    }

    suspend fun markConversationRead(conversationId: String) {
        val me = session.currentUserId ?: return
        messageDao.markConversationRead(conversationId, me)
        conversationDao.clearUnread(conversationId)
    }

    suspend fun pendingMessages(limit: Int = 20): List<Message> =
        messageDao.pendingMessages(limit).map { it.toDomain() }

    private suspend fun updateConversationSummary(conversationId: String, body: String, at: Long) {
        val pending = messageDao.pendingCount(conversationId)
        conversationDao.updateLastMessage(conversationId, body.take(80), at, pending)
    }

    private suspend fun updatePendingCount(conversationId: String) {
        val pending = messageDao.pendingCount(conversationId)
        val conversation = conversationDao.byId(conversationId) ?: return
        conversationDao.updateLastMessage(
            conversationId,
            conversation.lastMessagePreview,
            conversation.lastMessageAt,
            pending,
        )
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
        const val MAX_MESSAGE_LENGTH = 4000
        private const val DAY_MS = 86_400_000L
    }
}
