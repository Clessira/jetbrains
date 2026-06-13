package com.clessira.jetbrains.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Port of vscode/src/test/capability.test.ts. */
class CapabilityTest {
    @TempDir
    lateinit var tempDir: Path

    private fun write(content: String): Path {
        val file = tempDir.resolve("api-endpoint.json")
        Files.writeString(file, content)
        return file
    }

    @Test
    fun `reads a valid capability file`() {
        val file = write("""{"version":1,"socketPath":"/tmp/api.sock","token":"secret-token","pid":4242}""")
        val cap = Capability.read(file)
        assertEquals(1, cap.version)
        assertEquals("/tmp/api.sock", cap.socketPath)
        assertEquals("secret-token", cap.token)
        assertEquals(4242L, cap.pid)
    }

    @Test
    fun `throws when the file is missing`() {
        assertThrows(CapabilityException::class.java) {
            Capability.read(tempDir.resolve("does-not-exist.json"))
        }
    }

    @Test
    fun `throws on invalid JSON`() {
        val file = write("{not json")
        val e = assertThrows(CapabilityException::class.java) { Capability.read(file) }
        assertTrue(e.message!!.contains("not valid JSON"), e.message)
    }

    @Test
    fun `throws when the document is not an object`() {
        val file = write("[1,2,3]")
        val e = assertThrows(CapabilityException::class.java) { Capability.read(file) }
        assertTrue(e.message!!.contains("not an object"), e.message)
    }

    @Test
    fun `throws on an unsupported version`() {
        val file = write("""{"version":2,"socketPath":"/tmp/api.sock","token":"t","pid":1}""")
        val e = assertThrows(CapabilityException::class.java) { Capability.read(file) }
        assertTrue(e.message!!.contains("unsupported capability version"), e.message)
    }

    @Test
    fun `throws on a missing version`() {
        val file = write("""{"socketPath":"/tmp/api.sock","token":"t","pid":1}""")
        assertThrows(CapabilityException::class.java) { Capability.read(file) }
    }

    @Test
    fun `throws on a missing or empty socketPath`() {
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"token":"t","pid":1}"""))
        }
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"","token":"t","pid":1}"""))
        }
    }

    @Test
    fun `throws on a missing or empty token`() {
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"/tmp/api.sock","pid":1}"""))
        }
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"/tmp/api.sock","token":"","pid":1}"""))
        }
    }

    @Test
    fun `throws on a missing or non-integer pid`() {
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"/tmp/api.sock","token":"t"}"""))
        }
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"/tmp/api.sock","token":"t","pid":3.5}"""))
        }
        assertThrows(CapabilityException::class.java) {
            Capability.read(write("""{"version":1,"socketPath":"/tmp/api.sock","token":"t","pid":"77"}"""))
        }
    }

    @Test
    fun `default path points into the sandbox container`() {
        val path = Capability.filePath().toString()
        assertTrue(path.endsWith("Library/Containers/com.mattes.nowdoing/Data/api-endpoint.json"), path)
    }
}
