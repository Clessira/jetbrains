package com.clessira.nowdoing.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovery file the Mac app writes alongside the loopback API socket.
 * Port of `vscode/src/capability.ts` — read on every request, never cached,
 * and any failure means "NowDoing is not currently reachable".
 */
data class ApiCapability(
    val version: Int,
    val socketPath: String,
    val token: String,
    val pid: Long,
)

class CapabilityException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Capability {
    /** Lives inside the sandbox container's `Data/` directory. */
    fun filePath(): Path = Path.of(
        System.getProperty("user.home"),
        "Library", "Containers", "com.mattes.nowdoing", "Data", "api-endpoint.json",
    )

    fun read(path: Path = filePath()): ApiCapability {
        val raw = try {
            Files.readString(path)
        } catch (e: IOException) {
            throw CapabilityException("capability file unreadable: ${e.message}", e)
        }
        val parsed: JsonElement = try {
            val reader = JsonReader(StringReader(raw))
            reader.isLenient = false
            JsonParser.parseReader(reader)
        } catch (e: Exception) {
            throw CapabilityException("capability file is not valid JSON", e)
        }
        if (!parsed.isJsonObject) {
            throw CapabilityException("capability file is not an object")
        }
        val obj = parsed.asJsonObject

        val versionElement = obj.get("version")
        val version = versionElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (version == null || version != 1.0) {
            throw CapabilityException("unsupported capability version: ${versionElement ?: "undefined"}")
        }

        val socketPath = obj.get("socketPath")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (socketPath.isNullOrEmpty()) {
            throw CapabilityException("capability file missing socketPath")
        }

        val token = obj.get("token")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (token.isNullOrEmpty()) {
            throw CapabilityException("capability file missing token")
        }

        val pidElement = obj.get("pid")
        val pid = pidElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
        if (pid == null || pid != Math.rint(pid)) {
            throw CapabilityException("capability file missing pid")
        }

        return ApiCapability(version = 1, socketPath = socketPath, token = token, pid = pid.toLong())
    }
}
