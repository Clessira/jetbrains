package com.clessira.nowdoing.core

object Endpoints {
    const val HEALTHCHECK = "/healthcheck"
    const val BRANCH_CHANGED = "/branch-changed"
    const val ACTIVITY_SEARCH = "/activities/search"
    const val ACTIVITY_START = "/activities/start"
    const val CURRENT = "/current"

    fun buildActivitySearchPath(query: String, limit: Int, basePath: String = ACTIVITY_SEARCH): String =
        "$basePath?q=${encodeURIComponent(query)}&limit=$limit"

    /**
     * JS `encodeURIComponent` equivalent. The canonical string signs the path
     * *including* the query, so the encoding must match what the VS Code
     * extension produces — `java.net.URLEncoder` does not (space → `+`, and it
     * escapes `!~*'()`).
     */
    fun encodeURIComponent(value: String): String {
        val unreservedExtra = "-_.!~*'()"
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in unreservedExtra) {
                out.append(ch)
            } else {
                out.append('%')
                out.append(Character.forDigit((code ushr 4) and 0x0F, 16).uppercaseChar())
                out.append(Character.forDigit(code and 0x0F, 16).uppercaseChar())
            }
        }
        return out.toString()
    }
}
