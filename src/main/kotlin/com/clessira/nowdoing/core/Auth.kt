package com.clessira.nowdoing.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC request signing for the NowDoing local API. Port of `vscode/src/auth.ts`;
 * the canonical string layout must match the Swift verifier in
 * `BranchChangeServer.swift` byte-for-byte:
 *
 *     METHOD \n PATH_WITH_QUERY \n TIMESTAMP \n NONCE \n SHA256_HEX_OF_BODY
 */
data class AuthHeaders(val timestamp: String, val nonce: String, val signature: String)

object Auth {
    private val secureRandom = SecureRandom()

    fun buildAuthHeaders(
        method: String,
        requestPath: String,
        payload: ByteArray?,
        token: String,
        nowMs: () -> Long = System::currentTimeMillis,
        randomBytes: (Int) -> ByteArray = ::defaultRandomBytes,
    ): AuthHeaders {
        val timestamp = Math.floorDiv(nowMs(), 1000L).toString()
        val nonce = randomBytes(16).toHexLower()
        return AuthHeaders(timestamp, nonce, signRequest(method, requestPath, payload, token, timestamp, nonce))
    }

    fun signRequest(
        method: String,
        requestPath: String,
        payload: ByteArray?,
        token: String,
        timestamp: String,
        nonce: String,
    ): String {
        val bodyHash = sha256Hex(payload ?: ByteArray(0))
        val canonical = listOf(method, requestPath, timestamp, nonce, bodyHash).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHexLower()
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHexLower()

    private fun defaultRandomBytes(size: Int): ByteArray =
        ByteArray(size).also { secureRandom.nextBytes(it) }
}

internal fun ByteArray.toHexLower(): String {
    val hex = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return out.toString()
}
