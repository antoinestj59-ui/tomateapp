package tomate.core.crypto

import java.io.File
import java.security.KeyPair
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Interopérabilité réelle entre le chiffrement Kotlin (app Android) et le module WebCrypto de la
 * PWA (`web/js/e2ee.js`).
 *
 * Le test écrit les clés et un message chiffré par Kotlin, lance Node sur le **vrai** module de
 * la PWA, qui déchiffre, vérifie la signature, chiffre une réponse, puis Kotlin déchiffre cette
 * réponse. Si l'un des deux côtés change un octet du format, ce test casse.
 *
 * Si Node n'est pas disponible, le test est **ignoré** (et non « vert ») : `assumeTrue`.
 */
class E2eeInteropTest {

    private val aliceAgree: KeyPair = EcKeys.generate()
    private val aliceSign: KeyPair = EcKeys.generate()
    private val bobAgree: KeyPair = EcKeys.generate()
    private val bobSign: KeyPair = EcKeys.generate()

    private val aliceId = "11111111-1111-4111-8111-111111111111"
    private val bobId = "22222222-2222-4222-8222-222222222222"
    private val conversationId = "3f0e7d1a-1111-4a2b-9c3d-000000000009"
    private val message = "Bonjour Bob, message chiffré par l'app Android."

    @Test
    fun `la PWA dechiffre un message Kotlin et Kotlin dechiffre la reponse`() {
        val node = findNode()
        assumeTrue("Node.js absent : interopérabilité non vérifiée ici.", node != null)

        val module = locate("web/js/e2ee.js")
        val runner = locate("tooling/e2ee_interop.mjs")
        assumeTrue("Module PWA introuvable.", module.exists())
        assumeTrue("Script d'interopérabilité introuvable.", runner.exists())

        val workDir = File("build/e2ee-interop").apply { mkdirs() }
        val body = E2ee.encrypt(message, conversationId, aliceId, bobAgree.public, aliceSign.private)
        val fixture = File(workDir, "fixture.json")
        val output = File(workDir, "out.json")
        val safetyFromKotlin = E2ee.safetyNumber(
            aliceId, EcKeys.encodePublic(aliceAgree.public), EcKeys.encodePublic(aliceSign.public),
            bobId, EcKeys.encodePublic(bobAgree.public), EcKeys.encodePublic(bobSign.public),
        )

        fixture.writeText(
            """
            {
              "conversationId": "$conversationId",
              "aliceId": "$aliceId",
              "bobId": "$bobId",
              "body": "$body",
              "aliceAgreeKey": "${EcKeys.encodePublic(aliceAgree.public)}",
              "aliceAgreePrivate": "${EcKeys.encodePrivate(aliceAgree.private)}",
              "aliceSignKey": "${EcKeys.encodePublic(aliceSign.public)}",
              "aliceSignPrivate": "${EcKeys.encodePrivate(aliceSign.private)}",
              "bobAgreeKey": "${EcKeys.encodePublic(bobAgree.public)}",
              "bobAgreePrivate": "${EcKeys.encodePrivate(bobAgree.private)}",
              "bobSignKey": "${EcKeys.encodePublic(bobSign.public)}",
              "bobSignPrivate": "${EcKeys.encodePrivate(bobSign.private)}"
            }
            """.trimIndent(),
        )

        val process = ProcessBuilder(node, runner.absolutePath, module.absolutePath, fixture.absolutePath, output.absolutePath)
            .redirectErrorStream(true)
            .start()
        val log = process.inputStream.bufferedReader().readText()
        assertTrue("Node a échoué :\n$log", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("Node a échoué :\n$log", 0, process.exitValue())

        @Suppress("UNCHECKED_CAST")
        val result = JsonObject(output.readText())
        assertEquals("La PWA doit déchiffrer exactement le texte Kotlin.", message, result.string("plaintextFromKotlin"))
        assertEquals("Le numéro de sécurité doit être identique dans les deux implémentations.", safetyFromKotlin, result.string("safetyFromWeb"))
        assertTrue("La PWA doit refuser un message modifié.", result.bool("tamperRejected"))

        val reply = result.string("replyBody")
        assertTrue("La réponse doit être au format TOMATE.", E2ee.isEncrypted(reply))
        assertFalse("La réponse ne doit pas contenir le texte en clair.", reply.contains(result.string("replyText")))
        val parsed = E2eeMessage.parse(reply, conversationId)!!
        val decrypted = E2ee.decrypt(parsed, bobId, aliceAgree.private, bobSign.public)
        assertEquals(result.string("replyText"), decrypted)
    }

    private fun findNode(): String? {
        val candidates = listOf("/usr/bin/node", "/usr/local/bin/node", "/opt/homebrew/bin/node")
        return candidates.firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH")?.split(File.pathSeparator)
                ?.map { File(it, "node") }
                ?.firstOrNull { it.canExecute() }
                ?.absolutePath
    }

    /** Le répertoire de travail des tests Gradle est le module ; on remonte à la racine si besoin. */
    private fun locate(relative: String): File {
        val direct = File(relative)
        if (direct.exists()) return direct
        return File("../$relative")
    }

    /** Lecteur JSON minimal pour la sortie du script (aucune dépendance de test ajoutée). */
    private class JsonObject(source: String) {
        private val fields: Map<String, String> = Regex("\"(\\w+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|true|false|\\d+)")
            .findAll(source)
            .associate { match ->
                val raw = match.groupValues[2]
                val value = if (raw.startsWith("\"")) unescape(raw.substring(1, raw.length - 1)) else raw
                match.groupValues[1] to value
            }

        fun string(key: String): String = fields[key] ?: error("champ JSON absent : $key")

        fun bool(key: String): Boolean = string(key) == "true"

        private fun unescape(value: String): String =
            value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
    }

    @Suppress("unused")
    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
