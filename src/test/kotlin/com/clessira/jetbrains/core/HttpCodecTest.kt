package com.clessira.jetbrains.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class HttpCodecTest {

    @Test
    fun `encodes a GET request without a body`() {
        val bytes = HttpCodec.encodeRequest(
            "GET", "/healthcheck",
            linkedMapOf("X-Clessira-Token" to "tok"),
            null,
        )
        assertEquals(
            "GET /healthcheck HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "X-Clessira-Token: tok\r\n" +
                "\r\n",
            bytes.toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `encodes a POST request with a JSON body and content headers`() {
        val body = """{"branch":"main"}""".toByteArray()
        val bytes = HttpCodec.encodeRequest("POST", "/branch-changed", emptyMap(), body)
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("POST /branch-changed HTTP/1.1\r\n"))
        assertTrue("Content-Type: application/json\r\n" in text)
        assertTrue("Content-Length: ${body.size}\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n" + body.toString(Charsets.ISO_8859_1)))
    }

    @Test
    fun `a body-less POST still sends Content-Length 0`() {
        val text = HttpCodec.encodeRequest("POST", "/activities/stop", emptyMap(), null)
            .toString(Charsets.ISO_8859_1)
        assertTrue("Content-Length: 0\r\n" in text)
    }

    @Test
    fun `parses a content-length response in a single feed`() {
        val parser = HttpCodec.ResponseParser()
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
        parser.feed(raw.toByteArray(Charsets.ISO_8859_1))
        assertTrue(parser.isComplete)
        val response = parser.toResponse()
        assertEquals(200, response.status)
        assertEquals("application/json", response.headers["content-type"])
        assertEquals("""{"ok":true}""", response.bodyText)
    }

    @Test
    fun `parses a response fed one byte at a time`() {
        val parser = HttpCodec.ResponseParser()
        val raw = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 23\r\n\r\n{\"error\":\"bad request\"}"
            .toByteArray(Charsets.ISO_8859_1)
        for (b in raw) {
            assertFalse(parser.isComplete)
            parser.feed(byteArrayOf(b))
        }
        assertTrue(parser.isComplete)
        assertEquals(401, parser.toResponse().status)
        assertEquals("""{"error":"bad request"}""", parser.toResponse().bodyText)
    }

    @Test
    fun `a close-delimited body completes only at EOF`() {
        val parser = HttpCodec.ResponseParser()
        parser.feed("HTTP/1.1 200 OK\r\n\r\nhello".toByteArray(Charsets.ISO_8859_1))
        assertFalse(parser.isComplete)
        parser.feed(" world".toByteArray(Charsets.ISO_8859_1))
        assertFalse(parser.isComplete)
        parser.onEof()
        assertTrue(parser.isComplete)
        assertEquals("hello world", parser.toResponse().bodyText)
    }

    @Test
    fun `decodes a chunked body`() {
        val parser = HttpCodec.ResponseParser()
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        parser.feed(raw.toByteArray(Charsets.ISO_8859_1))
        assertTrue(parser.isComplete)
        assertEquals("Wikipedia", parser.toResponse().bodyText)
    }

    @Test
    fun `chunked body split across feeds completes once the terminator arrives`() {
        val parser = HttpCodec.ResponseParser()
        parser.feed("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWi".toByteArray(Charsets.ISO_8859_1))
        assertFalse(parser.isComplete)
        parser.feed("ki\r\n0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        assertTrue(parser.isComplete)
        assertEquals("Wiki", parser.toResponse().bodyText)
    }

    @Test
    fun `EOF before headers throws`() {
        val parser = HttpCodec.ResponseParser()
        parser.feed("HTTP/1.1 200".toByteArray(Charsets.ISO_8859_1))
        assertThrows(IOException::class.java) { parser.onEof() }
    }

    @Test
    fun `EOF before the full content-length body throws`() {
        val parser = HttpCodec.ResponseParser()
        parser.feed("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".toByteArray(Charsets.ISO_8859_1))
        assertThrows(IOException::class.java) { parser.onEof() }
    }
}
