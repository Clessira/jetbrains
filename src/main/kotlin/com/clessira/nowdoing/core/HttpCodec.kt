package com.clessira.nowdoing.core

import java.io.ByteArrayOutputStream
import java.io.IOException

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * (31 * status + headers.hashCode()) + body.contentHashCode()
}

/**
 * Minimal HTTP/1.1 framing for the loopback API. Pure functions / incremental
 * parser so everything is testable without sockets. One request per
 * connection (`Connection: close`), like the VS Code extension's node client.
 */
object HttpCodec {

    fun encodeRequest(
        method: String,
        pathWithQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): ByteArray {
        val head = StringBuilder()
        head.append(method).append(' ').append(pathWithQuery).append(" HTTP/1.1\r\n")
        head.append("Host: localhost\r\n")
        head.append("Connection: close\r\n")
        for ((name, value) in headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        if (body != null) {
            head.append("Content-Type: application/json\r\n")
            head.append("Content-Length: ").append(body.size).append("\r\n")
        } else if (method == "POST") {
            head.append("Content-Length: 0\r\n")
        }
        head.append("\r\n")
        val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        return if (body == null) headBytes else headBytes + body
    }

    /**
     * Incremental response parser: feed bytes as they arrive, signal EOF when
     * the peer closes. Supports Content-Length, chunked, and close-delimited
     * bodies (the Swift server uses Content-Length, the rest is defensive).
     */
    class ResponseParser {
        private val buffer = ByteArrayOutputStream(16 * 1024)
        private var eof = false

        var isComplete: Boolean = false
            private set
        private var status = 0
        private var headers: Map<String, String> = emptyMap()
        private var headerEnd = -1
        private var body: ByteArray = ByteArray(0)

        fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            if (isComplete) return
            buffer.write(data, offset, length)
            tryParse()
        }

        fun onEof() {
            if (isComplete) return
            eof = true
            tryParse()
        }

        fun toResponse(): HttpResponse {
            check(isComplete) { "response is not complete" }
            return HttpResponse(status, headers, body)
        }

        private fun tryParse() {
            val bytes = buffer.toByteArray()
            if (headerEnd < 0) {
                headerEnd = indexOfHeaderEnd(bytes)
                if (headerEnd < 0) {
                    if (eof) throw IOException("connection closed before response headers")
                    return
                }
                parseHead(bytes.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1))
            }
            val bodyStart = headerEnd + 4
            val available = bytes.copyOfRange(minOf(bodyStart, bytes.size), bytes.size)

            val transferEncoding = headers["transfer-encoding"]?.lowercase() ?: ""
            val contentLength = headers["content-length"]?.trim()?.toIntOrNull()
            when {
                "chunked" in transferEncoding -> {
                    val decoded = decodeChunked(available)
                    if (decoded != null) {
                        body = decoded
                        isComplete = true
                    } else if (eof) {
                        throw IOException("connection closed mid-chunk")
                    }
                }
                contentLength != null -> {
                    if (available.size >= contentLength) {
                        body = available.copyOfRange(0, contentLength)
                        isComplete = true
                    } else if (eof) {
                        throw IOException("connection closed before full response body")
                    }
                }
                else -> {
                    // Close-delimited: the body is everything until EOF.
                    if (eof) {
                        body = available
                        isComplete = true
                    }
                }
            }
        }

        private fun parseHead(head: String) {
            val lines = head.split("\r\n")
            val statusLine = lines.firstOrNull() ?: throw IOException("empty response head")
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
                throw IOException("malformed status line: $statusLine")
            }
            status = parts[1].toIntOrNull() ?: throw IOException("malformed status code: $statusLine")
            val parsedHeaders = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val sep = line.indexOf(':')
                if (sep <= 0) continue
                parsedHeaders[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
            }
            headers = parsedHeaders
        }

        /** Returns the decoded body, or null if more data is needed. */
        private fun decodeChunked(data: ByteArray): ByteArray? {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (true) {
                val lineEnd = indexOf(data, pos, '\r'.code.toByte(), '\n'.code.toByte())
                if (lineEnd < 0) return null
                val sizeLine = data.copyOfRange(pos, lineEnd).toString(Charsets.ISO_8859_1)
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw IOException("malformed chunk size: $sizeLine")
                pos = lineEnd + 2
                if (size == 0) return out.toByteArray() // trailers ignored
                if (data.size < pos + size + 2) return null
                out.write(data, pos, size)
                pos += size + 2 // skip chunk data + CRLF
            }
        }

        private fun indexOfHeaderEnd(bytes: ByteArray): Int {
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) return i
            }
            return -1
        }

        private fun indexOf(data: ByteArray, from: Int, first: Byte, second: Byte): Int {
            for (i in from..data.size - 2) {
                if (data[i] == first && data[i + 1] == second) return i
            }
            return -1
        }
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
}
