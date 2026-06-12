package com.clessira.nowdoing.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

// Wire DTOs mirroring vscode/src/extension.ts. Gson omits null fields on
// serialization, matching JSON.stringify's treatment of undefined.

data class ActivitySearchItem(
    val id: String,
    val name: String,
    val groupName: String? = null,
)

data class ActivitySearchResponse(val items: List<ActivitySearchItem>?)

data class ActivityStartBody(
    val activityID: String? = null,
    val name: String? = null,
    val createIfMissing: Boolean? = null,
)

data class ActivityStartResult(
    val activityID: String,
    val activityName: String,
    val created: Boolean,
)

data class ActivityStartResponse(val ok: Boolean, val result: ActivityStartResult?)

data class BranchChangeBody(
    val repo: String,
    val repoPath: String? = null,
    val branch: String,
    val previousBranch: String? = null,
)

data class CurrentActivityResult(
    val activityID: String,
    val activityName: String,
    val startedAt: String,
    val isOnBreak: Boolean,
)

data class CurrentActivityResponse(val ok: Boolean, val result: CurrentActivityResult?)

data class ErrorBody(val error: String?)

object Wire {
    val gson = Gson()

    /** Port of `util.ts parseJson` — null on blank input or any parse failure. */
    fun <T> parseJson(value: String, type: Class<T>): T? {
        if (value.isBlank()) return null
        return try {
            gson.fromJson(value, type)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    fun errorMessageFromResponse(status: Int, body: String): String {
        val error = parseJson(body, ErrorBody::class.java)?.error
        return if (error != null) "HTTP $status: $error" else "HTTP $status"
    }

    fun toJsonBytes(value: Any): ByteArray = gson.toJson(value).toByteArray(Charsets.UTF_8)
}
