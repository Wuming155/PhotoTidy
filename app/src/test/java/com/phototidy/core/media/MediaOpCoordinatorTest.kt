package com.phototidy.core.media

import com.phototidy.core.StringProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOpCoordinatorTest {

    private val strings = object : StringProvider {
        override fun get(resId: Int, vararg args: Any) = "res:$resId"
    }

    @Test
    fun `success path invokes onSuccess`() = runTest {
        val ops = MediaOpCoordinator(strings)
        var success = false
        ops.run(successMessage = "done", onSuccess = { success = true }) { MediaOpResult.Success }
        assertTrue(success)
    }

    @Test
    fun `failure path invokes onFailure with message`() = runTest {
        val ops = MediaOpCoordinator(strings)
        var failureMsg: String? = null
        ops.run(
            successMessage = "done",
            onFailure = { failureMsg = it },
        ) { MediaOpResult.Failed("boom") }
        assertEquals("boom", failureMsg)
    }

    @Test
    fun `concurrent operations are serialized instead of cancelling each other`() = runTest {
        // 协调器是进程级单例。历史实现里并发触发授权会让后到者把先到者的等待
        // 静默 cancel 掉（先到者收不到任何通知）；现在 gate 把它们排队 ——
        // 先到者走完整个流程，后到者才开始。
        val ops = MediaOpCoordinator(strings)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var firstFinished = false
        var secondFinished = false

        val first = launch {
            ops.runStaged(successMessage = "s1", consentBudget = 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                firstFinished = true
                MediaOpResult.Success
            }
        }
        firstStarted.await()

        val second = launch {
            ops.runStaged(successMessage = "s2", consentBudget = 1) {
                secondFinished = true
                MediaOpResult.Success
            }
        }
        // 第一个还在等待中，第二个必须排在 gate 外，不能先执行、也不能取消第一个
        runCurrent()
        assertFalse(secondFinished)
        assertTrue(firstStarted.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(firstFinished)
        assertTrue(secondFinished)
    }
}
