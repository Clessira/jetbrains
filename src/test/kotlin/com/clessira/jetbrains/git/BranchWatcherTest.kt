package com.clessira.jetbrains.git

import com.clessira.jetbrains.core.BranchWatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of vscode/src/test/repoWatcher.test.ts (virtual-time edition). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BranchWatcherTest {

    private class Recorder {
        val fired = mutableListOf<Pair<String, String?>>()
        val onChange: suspend (String, String?) -> Unit = { branch, previous ->
            fired += branch to previous
        }
    }

    @Test
    fun `the initial branch is never announced`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("main", this, { 1500L }, recorder.onChange)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(recorder.fired.isEmpty())
        watcher.dispose()
    }

    @Test
    fun `a branch switch fires once after the debounce window`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("main", this, { 1500L }, recorder.onChange)
        watcher.onStateChange("feature/x")
        advanceTimeBy(1499)
        runCurrent()
        assertTrue(recorder.fired.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("feature/x" to "main"), recorder.fired)
        watcher.dispose()
    }

    @Test
    fun `a rapid burst coalesces to the final branch with the directly preceding previousBranch`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("a", this, { 1500L }, recorder.onChange)
        watcher.onStateChange("b")
        advanceTimeBy(500)
        watcher.onStateChange("c")
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(listOf("c" to "b"), recorder.fired)
        watcher.dispose()
    }

    @Test
    fun `detached HEAD does not fire but still updates the last-known branch`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("main", this, { 1500L }, recorder.onChange)
        watcher.onStateChange(null)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(recorder.fired.isEmpty())
        assertNull(watcher.currentBranch)

        watcher.onStateChange("main")
        advanceTimeBy(1500)
        runCurrent()
        assertEquals(listOf("main" to null), recorder.fired)
        watcher.dispose()
    }

    @Test
    fun `re-announcing the same branch is a no-op`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("main", this, { 1500L }, recorder.onChange)
        watcher.onStateChange("main")
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(recorder.fired.isEmpty())
        watcher.dispose()
    }

    @Test
    fun `the debounce duration is re-read on every change`() = runTest {
        val recorder = Recorder()
        var debounce = 1500L
        val watcher = BranchWatcher("main", this, { debounce }, recorder.onChange)
        debounce = 100L
        watcher.onStateChange("quick")
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("quick" to "main"), recorder.fired)
        watcher.dispose()
    }

    @Test
    fun `dispose cancels a pending notification`() = runTest {
        val recorder = Recorder()
        val watcher = BranchWatcher("main", this, { 1500L }, recorder.onChange)
        watcher.onStateChange("feature/x")
        watcher.dispose()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(recorder.fired.isEmpty())
    }
}
