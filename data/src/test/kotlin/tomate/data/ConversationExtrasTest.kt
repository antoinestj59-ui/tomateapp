package tomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.model.Message
import tomate.data.extras.EventCodec
import tomate.data.extras.PollCodec

class ConversationExtrasTest {

    private fun message(
        id: String,
        senderId: String,
        body: String,
        createdAt: Long,
    ) = Message(
        id = id,
        conversationId = "conv",
        senderId = senderId,
        body = body,
        createdAt = createdAt,
    )

    @Test
    fun `sondage relu tel qu'il a été écrit`() {
        val body = PollCodec.encodePoll("Où mange-t-on ?", listOf("Chez Léa", "Au parc", "  "))
        assertNotNull(body)
        val poll = PollCodec.decodePoll(body!!)
        assertNotNull(poll)
        assertEquals("Où mange-t-on ?", poll!!.question)
        // Une option vide est écartée, jamais affichée comme un choix.
        assertEquals(listOf("Chez Léa", "Au parc"), poll.options)
    }

    @Test
    fun `sondage refusé quand il manque des options`() {
        assertNull(PollCodec.encodePoll("Question seule", listOf("Une option")))
        assertNull(PollCodec.encodePoll("   ", listOf("a", "b")))
        assertNull(PollCodec.decodePoll("un message ordinaire"))
        assertNull(PollCodec.decodePoll("[[tomate:poll:v1]]{pas du json"))
    }

    @Test
    fun `vote decode avec un identifiant contenant des deux-points`() {
        val encoded = PollCodec.encodeVote("conv:42", 2)
        val vote = PollCodec.decodeVote(encoded!!)
        assertNotNull(vote)
        assertEquals("conv:42", vote!!.pollId)
        assertEquals(2, vote.option)
        assertNull(PollCodec.decodeVote("[[tomate:vote:v1]]sans-index"))
        assertNull(PollCodec.decodeVote("texte"))
    }

    @Test
    fun `depouillement un votant une voix et le dernier vote gagne`() {
        val messages = listOf(
            message("1", "alice", PollCodec.encodeVote("p1", 0)!!, 100),
            message("2", "bob", PollCodec.encodeVote("p1", 1)!!, 200),
            // Alice change d'avis : son vote de 100 ne compte plus.
            message("3", "alice", PollCodec.encodeVote("p1", 1)!!, 300),
            // Un vote pour un autre sondage est ignoré.
            message("4", "bob", PollCodec.encodeVote("p2", 0)!!, 400),
            message("5", "carol", "bonjour", 500),
        )
        val tally = PollCodec.tally(messages, "p1", meId = "alice")
        assertEquals(2, tally.total)
        assertEquals(0, tally.countFor(0))
        assertEquals(2, tally.countFor(1))
        assertEquals(1, tally.mine)
        assertEquals(100, tally.percentFor(1))
    }

    @Test
    fun `depouillement vide sans vote`() {
        val tally = PollCodec.tally(emptyList(), "p1", meId = "alice")
        assertEquals(0, tally.total)
        assertNull(tally.mine)
        assertEquals(0, tally.percentFor(0))
    }

    @Test
    fun `evenement encode et relu avec une date valide`() {
        val body = EventCodec.encodeEvent(
            title = "Apéro TOMATE",
            startsAtIso = "2026-11-14T19:30:00Z",
            place = "Chez Léa",
        )
        assertNotNull(body)
        val event = EventCodec.decodeEvent(body!!)
        assertNotNull(event)
        assertEquals("Apéro TOMATE", event!!.title)
        assertEquals("Chez Léa", event.place)
        assertTrue(event.instant != null)
        assertNull(EventCodec.encodeEvent("Sans date", "demain"))
        assertNull(EventCodec.decodeEvent("[[tomate:event:v1]]{\"t\":\"x\",\"at\":\"pas une date\"}"))
    }

    @Test
    fun `protocole partage avec le web`() {
        // Ces chaînes viennent du client web : si Android les relit, les deux clients voient le
        // même sondage et comptent les mêmes votes.
        val webPoll = "[[tomate:poll:v1]]{\"q\":\"Test\",\"o\":[\"A\",\"B\"]}"
        val poll = PollCodec.decodePoll(webPoll)
        assertNotNull(poll)
        assertEquals(listOf("A", "B"), poll!!.options)

        val webVote = "[[tomate:vote:v1]]49deae70-1bbe-4310-a0bb-ed374c99658c:1"
        val vote = PollCodec.decodeVote(webVote)
        assertNotNull(vote)
        assertEquals("49deae70-1bbe-4310-a0bb-ed374c99658c", vote!!.pollId)
        assertEquals(1, vote.option)
    }
}
