package com.clessira.jetbrains.protocol

import com.clessira.jetbrains.core.Auth
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * In-process verifying server over a real Unix Domain Socket — a port of the
 * Swift `BranchChangeServer` verification rules exactly as pinned by
 * vscode/src/test/protocol.test.ts (timestamp drift, nonce format + replay,
 * signature format, constant-time compare). On success it serves canned JSON
 * per endpoint so the typed client can be exercised end-to-end.
 */
class FakeUdsServer(
    private val token: String,
    private val respond: Boolean = true,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : Closeable {

    // Bind under /tmp directly: sun_path is limited to ~104 bytes on macOS.
    val socketPath: Path = Path.of(
        "/tmp",
        "nd-test-${ProcessHandle.current().pid()}-${INSTANCE_COUNTER.incrementAndGet()}.sock",
    )

    private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val seenNonces = HashSet<String>()
    private val acceptThread: Thread

    @Volatile var lastTarget: String? = null
        private set
    @Volatile var lastBody: String? = null
        private set

    init {
        Files.deleteIfExists(socketPath)
        server.bind(UnixDomainSocketAddress.of(socketPath.toString()))
        acceptThread = Thread({ acceptLoop() }, "fake-uds-server").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { Files.deleteIfExists(socketPath) }
    }

    /** Closes the listener but leaves the socket file behind (ECONNREFUSED tests). */
    fun closeListenerKeepingSocketFile() {
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (server.isOpen) {
            val channel = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            try {
                handle(channel)
            } catch (_: Exception) {
                runCatching { channel.close() }
            }
        }
    }

    private fun handle(channel: SocketChannel) {
        val request = readRequest(channel) ?: run { channel.close(); return }
        if (!respond) return // hold the connection open forever (timeout tests)

        lastTarget = request.target
        lastBody = request.body.toString(Charsets.UTF_8)

        val (status, error) = verify(request)
        val payload = if (error != null) """{"error":"$error"}""" else routeSuccess(request)
        writeResponse(channel, status, payload)
        channel.close()
    }

    private data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun verify(request: Request): Pair<Int, String?> {
        val timestampRaw = request.headers["x-clessira-timestamp"] ?: ""
        if (!Regex("^-?\\d+$").matches(timestampRaw)) return 401 to "invalid timestamp"
        val timestamp = timestampRaw.toLongOrNull() ?: return 401 to "invalid timestamp"
        if (abs(nowSeconds() - timestamp) > MAX_TIMESTAMP_DRIFT_SECONDS) return 401 to "expired timestamp"

        val nonce = (request.headers["x-clessira-nonce"] ?: "").trim().lowercase()
        if (nonce.length < 16 || nonce.length > 128 || !Regex("^[a-z0-9]+$").matches(nonce)) {
            return 401 to "invalid nonce"
        }
        synchronized(seenNonces) {
            if (nonce in seenNonces) return 409 to "replay detected"
        }

        val signature = (request.headers["x-clessira-signature"] ?: "").trim().lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(signature)) return 401 to "invalid signature"

        val expected = Auth.signRequest(request.method, request.target, request.body, token, timestampRaw, nonce)
        if (!MessageDigest.isEqual(signature.toByteArray(), expected.toByteArray())) {
            return 401 to "bad signature"
        }

        synchronized(seenNonces) { seenNonces.add(nonce) }
        return 200 to null
    }

    private fun routeSuccess(request: Request): String = when {
        request.target == "/healthcheck" -> """{"ok":true}"""
        request.target == "/branch-changed" -> """{"ok":true}"""
        request.target == "/current" ->
            """{"ok":true,"result":{"activityID":"00000000-0000-0000-0000-000000000002",""" +
                """"activityName":"Deep Work","startedAt":"2026-06-12T08:00:00Z","isOnBreak":false}}"""
        request.target.startsWith("/activities/search") ->
            """{"items":[{"id":"00000000-0000-0000-0000-000000000001","name":"Code Review","groupName":"Work"},""" +
                """{"id":"00000000-0000-0000-0000-000000000002","name":"Deep Work"}]}"""
        request.target == "/activities/start" -> {
            val parsed = JsonParser.parseString(request.body.toString(Charsets.UTF_8)).asJsonObject
            val name = parsed.get("name")?.asString
            if (name != null) {
                """{"ok":true,"result":{"activityID":"00000000-0000-0000-0000-000000000003",""" +
                    """"activityName":"$name","created":true}}"""
            } else {
                """{"ok":true,"result":{"activityID":"${parsed.get("activityID").asString}",""" +
                    """"activityName":"Code Review","created":false}}"""
            }
        }
        else -> """{"error":"unknown endpoint"}"""
    }

    private fun readRequest(channel: SocketChannel): Request? {
        val buffer = ByteArrayOutputStream()
        val readBuffer = ByteBuffer.allocate(8192)

        fun readMore(): Boolean {
            readBuffer.clear()
            val n = channel.read(readBuffer)
            if (n < 0) return false
            readBuffer.flip()
            val chunk = ByteArray(n)
            readBuffer.get(chunk)
            buffer.write(chunk)
            return true
        }

        var headerEnd: Int
        while (true) {
            headerEnd = indexOfDoubleCrlf(buffer.toByteArray())
            if (headerEnd >= 0) break
            if (!readMore()) return null
        }

        val bytes = buffer.toByteArray()
        val head = bytes.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ', limit = 3)
        val method = requestLine[0]
        val target = requestLine[1]
        val headers = lines.drop(1).mapNotNull { line ->
            val sep = line.indexOf(':')
            if (sep <= 0) null else line.substring(0, sep).trim().lowercase() to line.substring(sep + 1).trim()
        }.toMap()

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        while (buffer.size() < headerEnd + 4 + contentLength) {
            if (!readMore()) return null
        }
        val all = buffer.toByteArray()
        val body = all.copyOfRange(headerEnd + 4, headerEnd + 4 + contentLength)
        return Request(method, target, headers, body)
    }

    private fun writeResponse(channel: SocketChannel, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = ByteBuffer.wrap(head.toByteArray(Charsets.ISO_8859_1) + body)
        while (out.hasRemaining()) channel.write(out)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        409 -> "Conflict"
        else -> "Error"
    }

    private fun indexOfDoubleCrlf(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return i
        }
        return -1
    }

    companion object {
        const val MAX_TIMESTAMP_DRIFT_SECONDS = 60L
        private val INSTANCE_COUNTER = AtomicInteger(0)
    }
}
