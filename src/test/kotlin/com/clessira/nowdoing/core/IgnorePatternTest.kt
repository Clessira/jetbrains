package com.clessira.nowdoing.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of vscode/src/test/branchPolicy.test.ts. */
class IgnorePatternTest {

    @Test
    fun `an empty or blank pattern never ignores`() {
        assertFalse(IgnorePattern.evaluate("", "main").isIgnored)
        assertFalse(IgnorePattern.evaluate("   ", "main").isIgnored)
        assertFalse(IgnorePattern.evaluate("", "main").invalidPattern)
    }

    @Test
    fun `an anchored alternation matches exactly`() {
        val pattern = "^(main|master|develop)$"
        assertTrue(IgnorePattern.evaluate(pattern, "main").isIgnored)
        assertTrue(IgnorePattern.evaluate(pattern, "develop").isIgnored)
        assertFalse(IgnorePattern.evaluate(pattern, "feature/main").isIgnored)
        assertFalse(IgnorePattern.evaluate(pattern, "main-2").isIgnored)
    }

    @Test
    fun `an unanchored pattern matches anywhere in the branch name`() {
        assertTrue(IgnorePattern.evaluate("wip", "feature/wip-experiment").isIgnored)
        assertTrue(IgnorePattern.evaluate("release/", "release/1.2").isIgnored)
        assertFalse(IgnorePattern.evaluate("hotfix", "feature/cleanup").isIgnored)
    }

    @Test
    fun `an invalid regex is flagged and never ignores`() {
        val result = IgnorePattern.evaluate("([unclosed", "main")
        assertTrue(result.invalidPattern)
        assertFalse(result.isIgnored)
    }

    @Test
    fun `the pattern is trimmed before compiling`() {
        assertTrue(IgnorePattern.evaluate("  ^main$  ", "main").isIgnored)
    }
}
