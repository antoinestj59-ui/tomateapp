package tomate.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.model.AppError
import tomate.core.model.Outcome
import tomate.data.db.OutboxDao
import tomate.data.db.OutboxEntity
import tomate.data.sync.OutboxRemote
import tomate.data.sync.SyncEngine

/**
 * Vérifie la règle qui cassait l'app : un rejeu ne doit jamais produire de doublon ni de
 * « conflit », et une opération refusée définitivement ne doit plus être comptée comme en attente.
 */
class SyncEngineTest {

    private class Call(
        val table: String,
        val body: String,
        val upsert: Boolean,
        val onConflict: String?,
        val ignoreDuplicates: Boolean,
    )

    private class FakeRemote : OutboxRemote {
        val inserts = mutableListOf<Call>()
        val updates = mutableListOf<Triple<String, String, String>>()
        var nextResult: Outcome<String> = Outcome.Success("")
        var selects: MutableList<String> = mutableListOf()

        override suspend fun insert(
            table: String,
            body: String,
            upsert: Boolean,
            onConflict: String?,
            representation: Boolean,
            ignoreDuplicates: Boolean,
        ): Outcome<String> {
            inserts += Call(table, body, upsert, onConflict, ignoreDuplicates)
            return nextResult
        }

        override suspend fun update(
            table: String,
            filter: List<Pair<String, String>>,
            body: String,
        ): Outcome<String> {
            updates += Triple(table, filter.joinToString { "${it.first}=${it.second}" }, body)
            return nextResult
        }

        override suspend fun delete(table: String, filter: List<Pair<String, String>>): Outcome<String> =
            Outcome.Success("")

        override suspend fun select(
            table: String,
            query: List<Pair<String, String>>,
            single: Boolean,
        ): Outcome<String> {
            selects += table
            return Outcome.Success("[]")
        }
    }

    /** Outbox en mémoire : seules comptent les colonnes que le moteur manipule. */
    private class FakeOutbox : OutboxDao {
        val rows = linkedMapOf<String, OutboxEntity>()

        override suspend fun enqueue(entry: OutboxEntity): Long {
            rows[entry.id] = entry
            return 1
        }

        override suspend fun due(now: Long, limit: Int): List<OutboxEntity> =
            rows.values.filter { it.nextAttemptAt <= now }.take(limit)

        override fun observeCount(): Flow<Int> = flowOf(live())

        override suspend fun count(): Int = live()

        private fun dead(): Int = rows.values.count { it.nextAttemptAt == Long.MAX_VALUE }

        private fun live(): Int = rows.values.count { it.nextAttemptAt != Long.MAX_VALUE }

        override fun observeDeadCount(): Flow<Int> = flowOf(dead())

        override suspend fun dead(limit: Int): List<OutboxEntity> =
            rows.values.filter { it.nextAttemptAt == Long.MAX_VALUE }.take(limit)

        override fun observeLastFailure(): Flow<String?> = flowOf(
            rows.values.filter { it.nextAttemptAt == Long.MAX_VALUE }.lastOrNull()?.lastError,
        )

        override suspend fun reviveDead(): Int {
            var revived = 0
            rows.entries.forEach { (key, entry) ->
                if (entry.nextAttemptAt == Long.MAX_VALUE) {
                    rows[key] = entry.copy(attempts = 0, nextAttemptAt = 0, lastError = null)
                    revived++
                }
            }
            return revived
        }

        override suspend fun discardDead(): Int {
            val doomed = rows.filterValues { it.nextAttemptAt == Long.MAX_VALUE }.keys.toList()
            doomed.forEach { rows.remove(it) }
            return doomed.size
        }

        override suspend fun total(): Int = rows.size

        override suspend fun done(id: String) {
            rows.remove(id)
        }

        override suspend fun reschedule(id: String, nextAttemptAt: Long, error: String?) {
            rows[id]?.let { rows[id] = it.copy(attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt, lastError = error) }
        }

        override suspend fun byKey(kind: String, key: String): OutboxEntity? =
            rows.values.firstOrNull { it.kind == kind && it.idempotencyKey == key }

        override suspend fun rekeyPayload(oldId: String, newId: String) = Unit
    }

    private fun entry(kind: String, payload: String, id: String = "e1") = OutboxEntity(
        id = id,
        idempotencyKey = "$kind:$id",
        kind = kind,
        payload = payload,
        createdAt = 0,
        attempts = 0,
        nextAttemptAt = 0,
        lastError = null,
    )

    @Test
    fun `replaying a story insert cannot create a duplicate`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("story_insert", """{"id":"story-1","body":"hello"}"""))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.succeeded)
        val call = remote.inserts.single()
        assertEquals("stories", call.table)
        assertTrue("un rejeu doit ignorer le doublon", call.ignoreDuplicates)
        assertEquals("id", call.onConflict)
        assertEquals(0, outbox.total())
    }

    @Test
    fun `replaying a community join ignores an existing membership`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(
            entry("community_join", """{"community_id":"c1","user_id":"u1","role":"Member"}"""),
        )

        SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        val call = remote.inserts.single()
        assertEquals("community_members", call.table)
        assertTrue(call.ignoreDuplicates)
        assertEquals("community_id,user_id", call.onConflict)
        assertFalse("jamais un merge : il exigerait un droit UPDATE", call.upsert)
    }

    @Test
    fun `replaying a reaction targets the real primary key`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("reaction_add", """{"post_id":"p1","user_id":"u1","kind":"tomate"}"""))

        SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals("post_id,user_id,kind", remote.inserts.single().onConflict)
    }

    @Test
    fun `a refused operation leaves the pending count but stays visible`() = runTest {
        val remote = FakeRemote()
        remote.nextResult = Outcome.Failure(AppError.Conflict("23505"))
        val outbox = FakeOutbox()
        outbox.enqueue(entry("post_insert", """{"id":"p1","body":"x"}"""))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.dropped)
        assertEquals("plus rien n'est « en attente »", 0, outbox.count())
        assertEquals("mais l'opération reste consultable", 1, outbox.dead(10).size)
        assertEquals(1, outbox.total())
    }

    @Test
    fun `a transient failure is rescheduled, not dropped`() = runTest {
        val remote = FakeRemote()
        remote.nextResult = Outcome.Failure(AppError.Server("HTTP 503"))
        val outbox = FakeOutbox()
        outbox.enqueue(entry("message_send", """{"id":"m1","body":"x"}"""))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.rescheduled)
        assertEquals(1, outbox.count())
        assertEquals(0, outbox.dead(10).size)
    }

    @Test
    fun `retrying a refused operation puts it back in the queue and sends it`() = runTest {
        val remote = FakeRemote()
        remote.nextResult = Outcome.Failure(AppError.Conflict("23505"))
        val outbox = FakeOutbox()
        outbox.enqueue(entry("post_insert", """{"id":"p1","body":"x"}"""))
        SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        remote.nextResult = Outcome.Success("")
        val revived = SyncEngine(remote, outbox, clock = { 2_000 }).retryFailed()

        assertEquals(1, revived)
        assertEquals(2, remote.inserts.size)
        assertEquals(0, outbox.total())
    }

    @Test
    fun `discarding a refused operation removes it for good`() = runTest {
        val remote = FakeRemote()
        remote.nextResult = Outcome.Failure(AppError.Validation("bad", "Données invalides."))
        val outbox = FakeOutbox()
        outbox.enqueue(entry("post_insert", """{"id":"p1"}"""))
        SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        val discarded = SyncEngine(remote, outbox, clock = { 2_000 }).discardFailed()

        assertEquals(1, discarded)
        assertEquals(0, outbox.total())
    }

    @Test
    fun `replaying a post update patches the row by id`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("post_update", """{"id":"p1","body":"nouveau"}"""))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.succeeded)
        val update = remote.updates.single()
        assertEquals("posts", update.first)
        assertTrue("le PATCH cible la publication", update.second.contains("p1"))
        assertTrue("le corps porte le nouveau texte", update.third.contains("nouveau"))
    }

    @Test
    fun `replaying a conversation rename patches the title`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("conversation_rename", """{"conversation_id":"c1","title":"Groupe Jardin"}"""))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.succeeded)
        val update = remote.updates.single()
        assertEquals("conversations", update.first)
        assertTrue(update.second.contains("c1"))
        assertTrue(update.third.contains("Groupe Jardin"))
    }

    @Test
    fun `replaying a message reaction ignores duplicates`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("message_reaction_add", """{"message_id":"m1","user_id":"u1","emoji":"🍅"}"""))

        SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        val call = remote.inserts.single()
        assertEquals("message_reactions", call.table)
        assertTrue(call.ignoreDuplicates)
        assertEquals("message_id,user_id,emoji", call.onConflict)
    }

    @Test
    fun `an unknown kind is refused instead of retried forever`() = runTest {
        val remote = FakeRemote()
        val outbox = FakeOutbox()
        outbox.enqueue(entry("mystery_op", "{}"))

        val report = SyncEngine(remote, outbox, clock = { 1_000 }).drain()

        assertEquals(1, report.dropped)
        assertTrue(remote.inserts.isEmpty())
    }
}
