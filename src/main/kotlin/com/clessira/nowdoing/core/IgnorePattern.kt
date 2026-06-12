package com.clessira.nowdoing.core

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class WatchIgnorePatternEvaluation(
    val isIgnored: Boolean,
    val invalidPattern: Boolean,
)

object IgnorePattern {
    /**
     * Port of `util.ts evaluateWatchIgnorePattern`. JS `regex.test` searches
     * anywhere in the string, so this uses `find()` rather than `matches()`.
     * An invalid pattern never ignores a branch; it is only flagged.
     */
    fun evaluate(pattern: String, branch: String): WatchIgnorePatternEvaluation {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return WatchIgnorePatternEvaluation(isIgnored = false, invalidPattern = false)
        }
        return try {
            val regex = Pattern.compile(trimmed)
            WatchIgnorePatternEvaluation(isIgnored = regex.matcher(branch).find(), invalidPattern = false)
        } catch (_: PatternSyntaxException) {
            WatchIgnorePatternEvaluation(isIgnored = false, invalidPattern = true)
        }
    }
}
