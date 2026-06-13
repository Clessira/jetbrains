package com.clessira.jetbrains.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Golden vectors generated from the VS Code extension's `signRequest`
 * (vscode/src/auth.ts) via node — they pin this port to the exact bytes the
 * Swift verifier expects. Regenerate with node's crypto if the scheme changes.
 */
class AuthTest {
    private val token = "test-token-please-ignore-1234567890abcdef"
    private val timestamp = "1749722400"
    private val nonce = "00112233445566778899aabbccddeeff"

    @Test
    fun `golden vector - GET healthcheck without body`() {
        assertEquals(
            "edac261974430a8a39e8f44cc13b194b24c289c9a65819c29b39c1a43062ad0a",
            Auth.signRequest("GET", "/healthcheck", null, token, timestamp, nonce),
        )
    }

    @Test
    fun `golden vector - POST branch-changed with JSON body`() {
        val body = """{"repo":"ClessiraMac","repoPath":"/Users/x/dev/ClessiraMac","branch":"feature/x","previousBranch":"main"}"""
            .toByteArray(Charsets.UTF_8)
        assertEquals(
            "89c266e4a0cfc0163c451843c1e5b26095bb550d62acb646ae0e9c17e8de796e",
            Auth.signRequest("POST", "/branch-changed", body, token, timestamp, nonce),
        )
    }

    @Test
    fun `gson serializes BranchChangeBody exactly like JSON stringify`() {
        val body = BranchChangeBody(
            repo = "ClessiraMac",
            repoPath = "/Users/x/dev/ClessiraMac",
            branch = "feature/x",
            previousBranch = "main",
        )
        assertEquals(
            """{"repo":"ClessiraMac","repoPath":"/Users/x/dev/ClessiraMac","branch":"feature/x","previousBranch":"main"}""",
            Wire.toJsonBytes(body).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `golden vector - canonical path includes encoded query string`() {
        val path = Endpoints.buildActivitySearchPath("café arbeit & (review)!*~", 20)
        assertEquals("/activities/search?q=caf%C3%A9%20arbeit%20%26%20(review)!*~&limit=20", path)
        assertEquals(
            "508cc522e11addf51281123e9d31e9033a2042680df94312ff10f5728df597a1",
            Auth.signRequest("GET", path, null, token, timestamp, nonce),
        )
    }

    @Test
    fun `golden vector - empty POST body hashes sha256 of empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Auth.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "a8ddcc105d0fa3d72d8770f4780c33af0242fadb2cd5f8da0e89286da97c9a21",
            Auth.signRequest("POST", "/branch-changed", ByteArray(0), token, timestamp, nonce),
        )
    }

    @Test
    fun `empty body and null body sign identically`() {
        assertEquals(
            Auth.signRequest("POST", "/x", null, token, timestamp, nonce),
            Auth.signRequest("POST", "/x", ByteArray(0), token, timestamp, nonce),
        )
    }

    @Test
    fun `buildAuthHeaders derives unix-seconds timestamp and 32-char lowercase hex nonce`() {
        val headers = Auth.buildAuthHeaders(
            method = "GET",
            requestPath = "/healthcheck",
            payload = null,
            token = token,
            nowMs = { 1749722400123L },
            randomBytes = { size -> ByteArray(size) { it.toByte() } },
        )
        assertEquals("1749722400", headers.timestamp)
        assertEquals("000102030405060708090a0b0c0d0e0f", headers.nonce)
        assertEquals(
            Auth.signRequest("GET", "/healthcheck", null, token, headers.timestamp, headers.nonce),
            headers.signature,
        )
    }

    @Test
    fun `default nonce satisfies the server's format rules`() {
        val headers = Auth.buildAuthHeaders("GET", "/healthcheck", null, token)
        assertTrue(Regex("^[a-z0-9]{32}$").matches(headers.nonce), "nonce ${headers.nonce}")
        assertTrue(Regex("^[0-9a-f]{64}$").matches(headers.signature))
    }
}
