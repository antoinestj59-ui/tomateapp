package tomate.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tomate.core.model.Message
import tomate.data.extras.CallCodec
import tomate.data.extras.CallSignal
import tomate.data.extras.ConversationExtras

class CallCodecTest {

    private fun message(body: String, sender: String = "peer", at: Long = 1_000L, id: String = body) =
        Message(
            id = id,
            conversationId = "c1",
            senderId = sender,
            body = body,
            createdAt = at,
        )

    @Test
    fun `un signal court tient dans un seul message decode par le web`() {
        val signal = CallSignal(kind = "end", callId = "abc")
        val chunks = CallCodec.pack(signal, id = "pack-1")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].startsWith(ConversationExtras.CALL_PREFIX))
        assertTrue(chunks[0].removePrefix(ConversationExtras.CALL_PREFIX).startsWith("pack-1|0|1|"))

        val collected = CallCodec.collect(listOf(message(chunks[0])), meId = "me")
        assertEquals(1, collected.size)
        assertEquals("end", collected[0].kind)
        assertEquals("abc", collected[0].callId)
        assertEquals(1_000L, collected[0].at)
    }

    @Test
    fun `un SDP long est decoupe en morceaux de moins de 4000 caracteres`() {
        val sdp = buildString {
            repeat(160) { append("a=rtpmap:$it opus/48000/2\r\n") }
        }
        val offer = CallSignal(kind = "offer", callId = "call-42", video = true, sdp = sdp)
        val chunks = CallCodec.pack(offer, id = "pack-long")

        assertTrue("attendu plusieurs morceaux, obtenu ${chunks.size}", chunks.size > 1)
        chunks.forEach { assertTrue("morceau trop long : ${it.length}", it.length < 4000) }

        val collected = CallCodec.collect(chunks.map { message(it) }, meId = "me")
        assertEquals(1, collected.size)
        assertEquals(sdp, collected[0].sdp)
        assertEquals(true, collected[0].video)
    }

    @Test
    fun `un paquet incomplet est ignore plutot qu applique a moitie`() {
        val offer = CallSignal(
            kind = "offer",
            callId = "call-1",
            sdp = "x=" + "b".repeat(5_000),
        )
        val chunks = CallCodec.pack(offer, id = "pack-2")
        assertTrue(chunks.size > 1)

        val collected = CallCodec.collect(chunks.dropLast(1).map { message(it) }, meId = "me")
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `ses propres morceaux ne sont jamais rejoues`() {
        val chunks = CallCodec.pack(CallSignal(kind = "offer", callId = "c", sdp = "v=0"), id = "p")
        val collected = CallCodec.collect(chunks.map { message(it, sender = "me") }, meId = "me")
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `les morceaux produits par la PWA sont relus par Android`() {
        // Corps tel que `packSignal` du web l'écrit : base64 du JSON, séparateurs `|`.
        val json = """{"kind":"ice","callId":"z","candidate":{"candidate":"candidate:1 1 udp 1 10.0.0.1 5000 typ host","sdpMid":"0","sdpMLineIndex":0}}"""
        val payload = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val body = "${ConversationExtras.CALL_PREFIX}web-pack|0|1|$payload"

        val collected = CallCodec.collect(listOf(message(body, at = 7)), meId = "me")
        assertEquals(1, collected.size)
        val ice = collected[0].iceParts()
        assertNotNull(ice)
        assertEquals("0", ice!!.first)
        assertEquals(0, ice.second)
        assertEquals("candidate:1 1 udp 1 10.0.0.1 5000 typ host", ice.third)
    }

    @Test
    fun `un signal Android est relu par le collecteur du web`() {
        // Même algorithme que `collectSignals` du web, appliqué au morceau produit ici.
        val signal = CallSignal(
            kind = "answer",
            callId = "fr-1",
            sdp = "v=0\r\ns=accentué\r\n",
        )
        val body = CallCodec.pack(signal, id = "p").single().removePrefix(ConversationExtras.CALL_PREFIX)
        val (id, index, total, chunk) = body.split('|', limit = 4)
        assertEquals("p", id)
        assertEquals("0", index)
        assertEquals("1", total)
        val decoded = String(java.util.Base64.getDecoder().decode(chunk))
        assertTrue(decoded.contains("accentué"))
        assertTrue(decoded.contains("\"answer\""))
    }

    @Test
    fun `un candidat vide ne produit pas de signal ICE exploitable`() {
        val broken = CallSignal(kind = "ice", callId = "c", candidate = buildJsonObject { put("sdpMid", "0") })
        assertNull(broken.iceParts())
    }
}
