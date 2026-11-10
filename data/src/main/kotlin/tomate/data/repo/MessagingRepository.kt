package tomate.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.builtins.ListSerializer
import tomate.core.crypto.E2ee
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
import tomate.data.e2ee.PeerKeySource
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
 * E2EE : les conversations 1-1 sont chiffrées avant d'être écrites en base, donc Room, l'outbox et
 * Supabase ne voient que du texte chiffré. Les groupes restent en clair et l'interface l'affiche.
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
        messageDao.observeForConversation(conversationId).transform { rows ->
            emit(rows.map { row -> decryptForDisplay(conversationId, row) })
        }

    /**
     * Cache mémoire des déchiffrements : un message déjà ouvert ne refait pas un accord ECDH à
     * chaque émission de la base. La clé est l'identifiant du message, la valeur son texte clair.
     */
    private val decryptedCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
    }

    private suspend fun decryptForDisplay(conversationId: String, row: MessageEntity): Message {
        val domain = row.toDomain()
        if (!E2ee.isEncrypted(row.body)) return domain
        synchronized(decryptedCache) { decryptedCache[row.id] }?.let { cached ->
            return domain.copy(body = cached, encrypted = true)
        }
        return when (val cipher = e2ee.openIncoming(conversationId, row.senderId, row.body)) {
            is E2eeEngine.Cipher.Ok -> {
                if (cipher.encrypted) synchronized(decryptedCache) { decryptedCache[row.id] = cipher.text }
                domain.copy(body = cipher.text, encrypted = true)
            }
            is E2eeEngine.Cipher.Failed -> domain.copy(body = "", encrypted = true, ciphertextUnreadable = true)
        }
    }

    /** L'autre participant d'une conversation 1-1, `null` pour un groupe ou un membre manquant. */
    suspend fun peerIdOf(conversationId: String): String? {
        val conversation = conversationDao.byId(conversationId) ?: return null
        if (conversation.isGroup) return null
        val me = session.currentUserId
        val others = conversationDao.members(conversationId).map { it.memberId }.filter { it != me }
        return others.singleOrNull()
    }

    suspend fun peerSource(conversationId: String): PeerKeySource? {
        val peer = peerIdOf(conversationId) ?: return null
        return (e2ee as? tomate.data.e2ee.E2eeService)?.peerSource(peer)
    }

    suspend fun safetyNumber(conversationId: String): String? {
        val peer = peerIdOf(conversationId) ?: return null
        return (e2ee as? tomate.data.e2ee.E2eeService)?.safetyNumber(peer)
    }

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

    /**
     * Local-first send. Never loses the message: on failure it stays pending in the outbox.
     *
     * [preview] remplace le texte affiché dans la liste des conversations quand le corps ne doit
     * pas y apparaître tel quel (un sondage s'affiche « 📊 question »). [updatePreview] à false
     * laisse l'aperçu intact : c'est le cas d'un vote, qui n'est pas un message lisible.
     */
    suspend fun send(
        conversationId: String,
        body: String,
        replyToId: String? = null,
        preview: String? = null,
        updatePreview: Boolean = true,
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

        // Chiffrement avant toute écriture : Room, l'outbox et Supabase ne voient que du chiffré.
        val peer = peerIdOf(conversationId)
        val cipher = e2ee.prepareOutgoing(conversationId, me, peer, trimmed)
        if (cipher is E2eeEngine.Cipher.Failed) {
            return Outcome.Failure(AppError.Validation("e2ee_failed", cipher.reason))
        }
        val outgoing = cipher as E2eeEngine.Cipher.Ok
        val encrypted = outgoing.encrypted

        val now = clock()
        val messageId = UUID.randomUUID().toString()
        val clientId = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = messageId,
            clientId = clientId,
            conversationId = conversationId,
            senderId = me,
            body = outgoing.text,
            createdAt = now,
            deliveryState = MessageDeliveryState.Pending,
            transport = null,
            encrypted = encrypted,
            replyToId = replyToId,
            attemptCount = 0,
            lastError = null,
        )
        messageDao.insert(entity)
        if (updatePreview) {
            // L'aperçu de la liste des conversations ne doit jamais contenir le texte clair d'un
            // message chiffré : il est aussi stocké en base et répliqué.
            val safePreview = if (encrypted) preview ?: "🔒 Message chiffré" else preview ?: trimmed
            updateConversationSummary(conversationId, safePreview, now)
        }

        val payload = SupabaseApi.json.encodeToString(
            MessageInsert.serializer(),
            MessageInsert(
                id = messageId,
                clientId = clientId,
                conversationId = conversationId,
                senderId = me,
                body = outgoing.text,
                encrypted = encrypted,
                replyToId = replyToId,
            ),
        )
        if (SessionManager.isLocalId(me)) {
            // Stays Pending: the message exists locally and will leave through the outbox or a
            // nearby transport. Nothing is claimed as delivered.
            enqueue("message_send", clientId, payload)
            updatePendingCount(conversationId)
            return Outcome.Success(entity.copy(body = trimmed).toDomain())
        }
        return when (val remote = api.insert("messages", payload, upsert = true, onConflict = "id")) {
            is Outcome.Success -> {
                messageDao.updateDelivery(messageId, MessageDeliveryState.Delivered.name, TransportKind.Internet.name, null)
                updatePendingCount(conversationId)
                Outcome.Success(
                    entity.copy(
                        body = trimmed,
                        deliveryState = MessageDeliveryState.Delivered,
                        transport = TransportKind.Internet,
                    ).toDomain(),
                )
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

    /**
     * True when the signed-in account created the conversation. The list screen offers no
     * "delete for everyone" button otherwise, so it is never a dead control.
     */
    suspend fun isConversationCreator(conversationId: String): Boolean {
        val me = session.currentUserId ?: return false
        val query = listOf(
            "select" to "id",
            "id" to "eq.$conversationId",
            "created_by" to "eq.$me",
        )
        val rows = api.select("conversations", query)
        if (rows !is Outcome.Success) return false
        return rows.value.trim() != "[]"
    }

    /**
     * Leaves a conversation: only this account's membership row is deleted, the other
     * participants keep the history. PostgREST answers 204 even when RLS filtered every row, so
     * the membership is read back instead of trusting the status code.
     */
    suspend fun leaveConversation(conversationId: String): Outcome<Unit> {
        val me = session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        val filter = listOf("conversation_id" to "eq.$conversationId", "user_id" to "eq.$me")
        when (val remote = api.delete("conversation_members", filter)) {
            is Outcome.Failure -> return remote
            is Outcome.Success -> Unit
        }
        val remaining = api.select("conversation_members", filter + ("select" to "conversation_id"))
        return when (remaining) {
            is Outcome.Failure -> remaining
            is Outcome.Success ->
                if (remaining.value.trim() != "[]") {
                    Outcome.Failure(AppError.Unknown("Le serveur n'a pas retiré votre adhésion."))
                } else {
                    forgetLocally(conversationId)
                    Outcome.Success(Unit)
                }
        }
    }

    /**
     * Deletes a conversation for every participant. Only the creator can do it: the server
     * policy `conversations_delete_creator` (migration 0008) enforces it, and the row is read
     * back so a refused delete is reported instead of shown as a success.
     */
    suspend fun deleteConversation(conversationId: String): Outcome<Unit> {
        session.currentUserId ?: return Outcome.Failure(AppError.Unauthorized())
        if (!isConversationCreator(conversationId)) {
            return Outcome.Failure(AppError.Unknown("Seul le créateur peut supprimer cette conversation."))
        }
        when (val remote = api.delete("conversations", listOf("id" to "eq.$conversationId"))) {
            is Outcome.Failure -> return remote
            is Outcome.Success -> Unit
        }
        val stillThere = api.select("conversations", listOf("select" to "id", "id" to "eq.$conversationId"))
        return when (stillThere) {
            is Outcome.Failure -> stillThere
            is Outcome.Success ->
                if (stillThere.value.trim() != "[]") {
                    Outcome.Failure(
                        AppError.Unknown(
                            "Suppression refusée : appliquez la migration 0008_conversations_delete.sql.",
                        ),
                    )
                } else {
                    forgetLocally(conversationId)
                    Outcome.Success(Unit)
                }
        }
    }

    private suspend fun forgetLocally(conversationId: String) {
        messageDao.deleteForConversation(conversationId)
        conversationDao.deleteMembers(conversationId)
        conversationDao.delete(conversationId)
    }

    suspend fun markConversationRead(conversationId: String) {
        val me = session.currentUserId ?: return
        messageDao.markConversationRead(conversationId, me)
        conversationDao.clearUnread(conversationId)
    }

    /**
     * Renomme une conversation de groupe. La politique `conversations_update_member`
     * l'autorise déjà aux membres : aucune migration requise.
     */
    suspend fun renameConversation(conversationId: String, title: String): Outcome<Unit> {
        val trimmed = title.trim().take(80)
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError.Validation("empty_title", "Le titre est vide."))
        }
        val current = conversationDao.byId(conversationId) ?: return Outcome.Failure(AppError.NotFound())
        conversationDao.upsert(current.copy(title = trimmed))
        val payload = """{"conversation_id":"$conversationId","title":${quoteJson(trimmed)}}"""
        if (session.isLocalAccount()) {
            enqueue("conversation_rename", conversationId, payload)
            return Outcome.Success(Unit)
        }
        return when (val remote = api.update("conversations", listOf("id" to "eq.$conversationId"), payload)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                enqueue("conversation_rename", conversationId, payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Réactions aux messages : table `message_reactions` (migration 0010). */
    suspend fun toggleMessageReaction(messageId: String, emoji: String, currentlyReacted: Boolean): Outcome<Unit> {
        val me = session.ensureActor().getOrNull()?.userId
            ?: return Outcome.Failure(AppError.Unauthorized())
        val message = messageDao.byId(messageId) ?: return Outcome.Failure(AppError.NotFound())
        val payload =
            """{"message_id":"$messageId","user_id":"$me","emoji":${quoteJson(emoji)}}"""
        if (SessionManager.isLocalId(me)) {
            enqueue(if (currentlyReacted) "message_reaction_remove" else "message_reaction_add", "$messageId:$me:$emoji", payload)
            return Outcome.Success(Unit)
        }
        if (currentlyReacted) {
            val filter = listOf(
                "message_id" to "eq.$messageId",
                "user_id" to "eq.$me",
                "emoji" to "eq.$emoji",
            )
            return when (val remote = api.delete("message_reactions", filter)) {
                is Outcome.Success -> Outcome.Success(Unit)
                is Outcome.Failure -> {
                    if (remote.error is AppError.SchemaMissing) {
                        return Outcome.Failure(
                            AppError.SchemaMissing("Réactions non activées : appliquez la migration 0010_message_reactions.sql."),
                        )
                    }
                    enqueue("message_reaction_remove", "$messageId:$me:$emoji", payload)
                    Outcome.Failure(remote.error)
                }
            }
        }
        return when (val remote = api.insert("message_reactions", payload, ignoreDuplicates = true, onConflict = "message_id,user_id,emoji", representation = false)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> {
                if (remote.error is AppError.SchemaMissing) {
                    return Outcome.Failure(
                        AppError.SchemaMissing("Réactions non activées : appliquez la migration 0010_message_reactions.sql."),
                    )
                }
                enqueue("message_reaction_add", "$messageId:$me:$emoji", payload)
                Outcome.Failure(remote.error)
            }
        }
    }

    /** Compte les réactions par emoji pour les messages donnés (lecture seule, sans cache). */
    suspend fun fetchMessageReactions(messageIds: List<String>): Outcome<Map<String, Map<String, Int>>> {
        if (messageIds.isEmpty() || session.isLocalAccount()) return Outcome.Success(emptyMap())
        val filter = "in.(" + messageIds.joinToString(",") + ")"
        return when (val response = api.select("message_reactions", listOf("select" to "message_id,emoji", "message_id" to filter))) {
            is Outcome.Failure -> {
                if (response.error is AppError.SchemaMissing) Outcome.Success(emptyMap()) else response
            }
            is Outcome.Success -> {
                val tally = mutableMapOf<String, MutableMap<String, Int>>()
                runCatching {
                    val array = SupabaseApi.json.parseToJsonElement(response.value) as? kotlinx.serialization.json.JsonArray
                    array?.forEach { row ->
                        val obj = row as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        val mid = obj["message_id"]?.toString()?.trim('"') ?: return@forEach
                        val emoji = obj["emoji"]?.toString()?.trim('"') ?: return@forEach
                        tally.getOrPut(mid) { mutableMapOf() }.merge(emoji, 1, Int::plus)
                    }
                }
                Outcome.Success(tally)
            }
        }
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

/** Minimal JSON string quoting (avoids a serialization dependency for one scalar). */
internal fun quoteJson(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
