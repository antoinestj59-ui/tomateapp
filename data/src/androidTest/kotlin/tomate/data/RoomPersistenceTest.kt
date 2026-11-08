package tomate.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tomate.core.model.Visibility
import tomate.data.db.PostEntity
import tomate.data.db.ProfileEntity
import tomate.data.db.TomateDatabase

/**
 * Tests instrumentés de persistance. Ils nécessitent un appareil ou un émulateur :
 *
 *     ./gradlew :data:connectedDebugAndroidTest
 *
 * Ils ne sont PAS exécutés dans la VM de développement (aucun appareil, /dev/kvm absent),
 * et ne doivent donc pas être présentés comme validés.
 */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {

    private lateinit var db: TomateDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TomateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun profile(id: String, isMe: Boolean = false) = ProfileEntity(
        id = id,
        handle = "handle_$id",
        displayName = "Profil $id",
        bio = "",
        avatarUrl = null,
        visibility = Visibility.Public,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        pendingSync = false,
        isMe = isMe,
    )

    private fun post(id: String, authorId: String, deleted: Boolean = false) = PostEntity(
        id = id,
        authorId = authorId,
        authorHandle = "handle_$authorId",
        authorDisplayName = "Profil $authorId",
        body = "Bonjour depuis $id",
        communityId = null,
        visibility = Visibility.Public,
        createdAt = 2_000L,
        updatedAt = 2_000L,
        reactionCount = 0,
        commentCount = 0,
        reactedByMe = false,
        pendingSync = false,
        deleted = deleted,
    )

    @Test
    fun profile_isPersistedAndObserved() = runBlocking {
        db.profileDao().upsert(profile("a", isMe = true))

        val me = db.profileDao().me()
        assertNotNull(me)
        assertEquals("handle_a", me!!.handle)
        assertEquals("handle_a", db.profileDao().observeMe().first()?.handle)
        assertTrue(me.isMe)
    }

    @Test
    fun onlyOneRowIsMe() = runBlocking {
        db.profileDao().upsert(profile("a", isMe = true))
        db.profileDao().upsert(profile("b", isMe = false))

        assertEquals("a", db.profileDao().me()?.id)
        assertEquals(2, db.profileDao().search("handle").first().size)
    }

    @Test
    fun deletedPostsAreHiddenFromTheFeed() = runBlocking {
        db.postDao().upsertAll(
            listOf(post("p1", "a"), post("p2", "a", deleted = true)),
        )

        val feed = db.postDao().observeFeed(null, 50, 0).first()
        assertEquals(1, feed.size)
        assertEquals("p1", feed.first().id)
    }

    @Test
    fun signOutRemovesOnlyMyProfile() = runBlocking {
        db.profileDao().upsertAll(listOf(profile("a", isMe = true), profile("b")))

        db.profileDao().deleteMe()

        assertNull(db.profileDao().me())
        assertNotNull(db.profileDao().byId("b"))
    }

    @Test
    fun hiddenPostIsNotDeletedButFlagged() = runBlocking {
        db.postDao().upsert(post("p1", "a"))

        db.postDao().markDeleted("p1")

        val stored = db.postDao().byId("p1")
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
        assertFalse(db.postDao().observeFeed(null, 50, 0).first().isNotEmpty())
    }
}
