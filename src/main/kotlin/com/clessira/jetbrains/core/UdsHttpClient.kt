package com.clessira.jetbrains.core

import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/** Message must contain "timeout" so RetryPolicy classifies it as retryable. */
class ClessiraTimeoutException(detail: String) : IOException("timeout: $detail")

/** Connect-phase failure; "connection refused" in the message marks it retryable. */
class ClessiraConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Blocking HTTP/1.1 request over a Unix Domain Socket, with a single overall
 * deadline (default 4s, matching the VS Code extension's request timeout).
 * `java.net.http.HttpClient` cannot speak UDS, hence the hand-rolled client.
 * Callers must invoke this off the EDT.
 */
class UdsHttpClient(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    fun request(
        socketPath: String,
        method: String,
        pathWithQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                val key = channel.register(selector, 0)
                connect(channel, key, selector, socketPath, deadlineNanos)

                val request = ByteBuffer.wrap(HttpCodec.encodeRequest(method, pathWithQuery, headers, body))
                writeFully(channel, key, selector, request, deadlineNanos)

                val parser = HttpCodec.ResponseParser()
                val readBuffer = ByteBuffer.allocate(16 * 1024)
                val chunk = ByteArray(readBuffer.capacity())
                while (!parser.isComplete) {
                    readBuffer.clear()
                    val n = channel.read(readBuffer)
                    when {
                        n > 0 -> {
                            readBuffer.flip()
                            readBuffer.get(chunk, 0, n)
                            parser.feed(chunk, 0, n)
                        }
                        n == 0 -> awaitReadable(key, selector, deadlineNanos)
                        else -> parser.onEof()
                    }
                }
                return parser.toResponse()
            }
        }
    }

    private fun connect(
        channel: SocketChannel,
        key: SelectionKey,
        selector: Selector,
        socketPath: String,
        deadlineNanos: Long,
    ) {
        try {
            if (!channel.connect(UnixDomainSocketAddress.of(socketPath))) {
                key.interestOps(SelectionKey.OP_CONNECT)
                awaitSelect(selector, deadlineNanos, "connect")
                if (!channel.finishConnect()) {
                    throw ClessiraTimeoutException("connect to $socketPath")
                }
            }
        } catch (e: ConnectException) {
            throw ClessiraConnectException("connection refused: ${e.message}", e)
        } catch (e: ClessiraTimeoutException) {
            throw e
        } catch (e: IOException) {
            // Socket file missing/unreadable (app quit): intentionally NOT
            // phrased as "connection refused" so it is not retried, mirroring
            // the VS Code classification of ENOENT vs ECONNREFUSED.
            throw ClessiraConnectException("cannot reach socket $socketPath: ${e.message}", e)
        }
    }

    private fun writeFully(
        channel: SocketChannel,
        key: SelectionKey,
        selector: Selector,
        buffer: ByteBuffer,
        deadlineNanos: Long,
    ) {
        while (buffer.hasRemaining()) {
            val n = channel.write(buffer)
            if (n == 0) {
                key.interestOps(SelectionKey.OP_WRITE)
                awaitSelect(selector, deadlineNanos, "write")
            }
        }
    }

    private fun awaitReadable(key: SelectionKey, selector: Selector, deadlineNanos: Long) {
        key.interestOps(SelectionKey.OP_READ)
        awaitSelect(selector, deadlineNanos, "read")
    }

    private fun awaitSelect(selector: Selector, deadlineNanos: Long, phase: String) {
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
        if (remainingMs <= 0) throw ClessiraTimeoutException(phase)
        val ready = selector.select(remainingMs)
        selector.selectedKeys().clear()
        if (ready == 0 && System.nanoTime() >= deadlineNanos) {
            throw ClessiraTimeoutException(phase)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4000L
    }
}
