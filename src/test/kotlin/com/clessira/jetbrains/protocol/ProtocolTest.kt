package com.clessira.jetbrains.protocol

import com.clessira.jetbrains.core.ActivityStartBody
import com.clessira.jetbrains.core.ApiTransport
import com.clessira.jetbrains.core.Auth
import com.clessira.jetbrains.core.BranchChangeBody
import com.clessira.jetbrains.core.ClessiraApi
import com.clessira.jetbrains.core.ClessiraConnectException
import com.clessira.jetbrains.core.ClessiraHttpException
import com.clessira.jetbrains.core.ClessiraTimeoutException
import com.clessira.jetbrains.core.RetryPolicy
import com.clessira.jetbrains.core.UdsHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end wire-format tests through the real client stack
 * (capability file → signing → UDS transport) against [FakeUdsServer] —
 * a port of every case in vscode/src/test/protocol.test.ts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtocolTest {

    private val token = "test-token-please-ignore-1234567890abcdef"
    private lateinit var server: FakeUdsServer
    private lateinit var capabilityFile: Path
    private lateinit var api: ClessiraApi
    private val rawClient = UdsHttpClient()
    private lateinit var tempDir: Path

    @BeforeAll
    fun startServer() {
        tempDir = Files.createTempDirectory("clessira-protocol-test")
        server = FakeUdsServer(token)
        capabilityFile = tempDir.resolve("api-endpoint.json")
        Files.writeString(
            capabilityFile,
            """{"version":1,"socketPath":"${server.socketPath}","token":"$token","pid":4242}""",
        )
        api = ClessiraApi(ApiTransport(capabilityPath = { capabilityFile }))
    }

    @AfterAll
    fun stopServer() {
        server.close()
        tempDir.toFile().deleteRecursively()
    }

    private fun signedHeaders(
        method: String,
        path: String,
        payload: ByteArray? = null,
        signToken: String = token,
        nowMs: () -> Long = System::currentTimeMillis,
    ): MutableMap<String, String> {
        val auth = Auth.buildAuthHeaders(method, path, payload, signToken, nowMs)
        return linkedMapOf(
            "X-Clessira-Token" to token,
            "X-Clessira-Timestamp" to auth.timestamp,
            "X-Clessira-Nonce" to auth.nonce,
            "X-Clessira-Signature" to auth.signature,
        )
    }

    private fun rawRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        payload: ByteArray? = null,
    ) = rawClient.request(server.socketPath.toString(), method, path, headers, payload)

    // --- Happy paths through the typed client --------------------------------

    @Test
    fun `a freshly signed healthcheck passes verification`() {
        assertEquals(200, api.healthcheck())
    }

    @Test
    fun `a signed POST with a JSON body verifies end-to-end`() {
        val status = api.postBranchChange(
            BranchChangeBody(repo = "ClessiraMac", repoPath = "/tmp/ClessiraMac", branch = "main"),
        )
        assertEquals(200, status)
        assertTrue(server.lastBody!!.contains("\"branch\":\"main\""))
    }

    @Test
    fun `searchActivities signs the query string and parses the items`() {
        val items = api.searchActivities("café arbeit & (review)!*~", 20)
        assertEquals(2, items.size)
        assertEquals("Code Review", items[0].name)
        assertEquals("Work", items[0].groupName)
        assertEquals("/activities/search?q=caf%C3%A9%20arbeit%20%26%20(review)!*~&limit=20", server.lastTarget)
    }

    @Test
    fun `startActivity round-trips create-if-missing`() {
        val result = api.startActivity(ActivityStartBody(name = "Neue Aktivität", createIfMissing = true))
        assertTrue(result.created)
        assertEquals("Neue Aktivität", result.activityName)

        val existing = api.startActivity(ActivityStartBody(activityID = "00000000-0000-0000-0000-000000000001"))
        assertFalse(existing.created)
        assertEquals("00000000-0000-0000-0000-000000000001", existing.activityID)
    }

    @Test
    fun `current parses the tracked activity`() {
        val current = api.current()
        assertNotNull(current)
        assertEquals("Deep Work", current!!.activityName)
        assertFalse(current.isOnBreak)
    }

    // --- Verifier rules (raw client) ------------------------------------------

    @Test
    fun `the canonical includes the querystring - signing without it is rejected`() {
        val headers = signedHeaders("GET", "/activities/search")
        val res = rawRequest("GET", "/activities/search?q=foo&limit=20", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("bad signature"), res.bodyText)
    }

    @Test
    fun `a tampered body fails verification even with valid headers`() {
        val original = """{"branch":"main"}""".toByteArray()
        val tampered = """{"branch":"evil"}""".toByteArray()
        val headers = signedHeaders("POST", "/branch-changed", original)
        val res = rawRequest("POST", "/branch-changed", headers, tampered)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("bad signature"), res.bodyText)
    }

    @Test
    fun `a stale timestamp is rejected as expired`() {
        val headers = signedHeaders("GET", "/healthcheck", nowMs = { System.currentTimeMillis() - 120_000 })
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("expired timestamp"), res.bodyText)
    }

    @Test
    fun `a timestamp exactly at the 60-second drift boundary is accepted`() {
        val headers = signedHeaders("GET", "/healthcheck", nowMs = { System.currentTimeMillis() - 60_000 })
        assertEquals(200, rawRequest("GET", "/healthcheck", headers).status)
    }

    @Test
    fun `a timestamp 61 seconds in the past is rejected as expired`() {
        val headers = signedHeaders("GET", "/healthcheck", nowMs = { System.currentTimeMillis() - 61_000 })
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("expired timestamp"), res.bodyText)
    }

    @Test
    fun `reusing a nonce within the TTL is rejected as replay`() {
        val fixedNonce = "11223344556677889900aabbccddeeff"
        fun fixedHeaders(): Map<String, String> {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val signature = Auth.signRequest("GET", "/healthcheck", null, token, timestamp, fixedNonce)
            return linkedMapOf(
                "X-Clessira-Token" to token,
                "X-Clessira-Timestamp" to timestamp,
                "X-Clessira-Nonce" to fixedNonce,
                "X-Clessira-Signature" to signature,
            )
        }
        assertEquals(200, rawRequest("GET", "/healthcheck", fixedHeaders()).status)
        val replay = rawRequest("GET", "/healthcheck", fixedHeaders())
        assertEquals(409, replay.status)
        assertTrue(replay.bodyText.contains("replay detected"), replay.bodyText)
    }

    @Test
    fun `a missing timestamp header is rejected as invalid`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers.remove("X-Clessira-Timestamp")
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid timestamp"), res.bodyText)
    }

    @Test
    fun `a non-integer timestamp is rejected as invalid`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Timestamp"] = "not-a-number"
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid timestamp"), res.bodyText)
    }

    @Test
    fun `a nonce shorter than 16 chars is rejected as invalid`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Nonce"] = "deadbeefdeadbee" // 15 chars
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid nonce"), res.bodyText)
    }

    @Test
    fun `a nonce with non-alphanumeric characters is rejected as invalid`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Nonce"] = "deadbeef-deadbeef-deadbeef-dead"
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid nonce"), res.bodyText)
    }

    @Test
    fun `a signature of wrong length is rejected as invalid format`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Signature"] = "deadbeef"
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid signature"), res.bodyText)
    }

    @Test
    fun `a signature with non-hex characters is rejected as invalid format`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Signature"] = "z".repeat(64)
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("invalid signature"), res.bodyText)
    }

    @Test
    fun `an uppercase-hex signature is accepted`() {
        val headers = signedHeaders("GET", "/healthcheck")
        headers["X-Clessira-Signature"] = headers["X-Clessira-Signature"]!!.uppercase()
        assertEquals(200, rawRequest("GET", "/healthcheck", headers).status)
    }

    @Test
    fun `a signature signed with the wrong token is rejected as a bad signature`() {
        val headers = signedHeaders("GET", "/healthcheck", signToken = "WRONG-TOKEN")
        val res = rawRequest("GET", "/healthcheck", headers)
        assertEquals(401, res.status)
        assertTrue(res.bodyText.contains("bad signature"), res.bodyText)
    }

    @Test
    fun `a POST with an empty body signs and verifies correctly`() {
        val empty = ByteArray(0)
        val headers = signedHeaders("POST", "/branch-changed", empty)
        assertEquals(200, rawRequest("POST", "/branch-changed", headers, empty).status)
    }

    @Test
    fun `non-2xx responses surface as typed HTTP errors with the server message`() {
        val brokenApi = run {
            val file = tempDir.resolve("cap-wrong-token.json")
            Files.writeString(
                file,
                """{"version":1,"socketPath":"${server.socketPath}","token":"another-token-but-wrong","pid":1}""",
            )
            ClessiraApi(ApiTransport(capabilityPath = { file }))
        }
        val e = assertThrows(ClessiraHttpException::class.java) { brokenApi.healthcheck() }
        assertEquals(401, e.status)
        assertEquals("HTTP 401: bad signature", e.message)
    }

    // --- Transport failure modes ----------------------------------------------

    @Test
    fun `a server that never responds times out within the deadline`() {
        FakeUdsServer(token, respond = false).use { silent ->
            val client = UdsHttpClient(timeoutMs = 300)
            val headers = signedHeaders("GET", "/healthcheck")
            val started = System.nanoTime()
            val e = assertThrows(ClessiraTimeoutException::class.java) {
                client.request(silent.socketPath.toString(), "GET", "/healthcheck", headers, null)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 4000, "timed out after ${elapsedMs}ms")
            assertTrue(RetryPolicy.isRetryableNotifyError(e))
        }
    }

    @Test
    fun `a stale socket file with no listener is a retryable connection failure`() {
        val stale = FakeUdsServer(token)
        stale.closeListenerKeepingSocketFile()
        try {
            val headers = signedHeaders("GET", "/healthcheck")
            val e = assertThrows(IOException::class.java) {
                rawClient.request(stale.socketPath.toString(), "GET", "/healthcheck", headers, null)
            }
            assertTrue(RetryPolicy.isRetryableNotifyError(e), "expected retryable, got: $e")
        } finally {
            Files.deleteIfExists(stale.socketPath)
        }
    }

    @Test
    fun `a missing socket file fails without being classified retryable`() {
        val headers = signedHeaders("GET", "/healthcheck")
        val e = assertThrows(ClessiraConnectException::class.java) {
            rawClient.request("/tmp/nd-test-no-such-socket.sock", "GET", "/healthcheck", headers, null)
        }
        assertFalse(RetryPolicy.isRetryableNotifyError(e), "expected non-retryable, got: $e")
    }
}
