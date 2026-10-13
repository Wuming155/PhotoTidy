package com.phototidy.core.media

import android.content.IntentSender
import com.phototidy.R
import com.phototidy.core.StringProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaOpCoordinatorTest {

    private val strings = object : StringProvider {
        override fun get(resId: Int, vararg args: Any) = "res:$resId"
    }

    /**
     * JVM 单测里造一个可用的 IntentSender：android.jar 桩的构造器会抛 "Stub!"
     * （`returnDefaultValues` 只兜方法、兜不了构造器），用 Unsafe 绕过构造逻辑。
     * sun.misc 在 android.jar 的编译类路径上不可见，只能走反射；
     * 测试里只把它塞进请求对象，从不真正启动。
     */
    private fun stubIntentSender(): IntentSender {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val allocate = unsafeClass.methods.first { it.name == "allocateInstance" }
        return allocate.invoke(theUnsafe.get(null), IntentSender::class.java) as IntentSender
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

    @Test
    fun `cancelling a waiting operation releases the gate`() = runTest {
        // gate 属于「泄漏即永久死锁」的设施：第一个操作挂在授权等待上被取消时，
        // withLock 的 finally 必须让出闸，第二个操作要能照常跑完。
        val ops = MediaOpCoordinator(strings)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ops.consent.collect {}
        }
        val firstStarted = CompletableDeferred<Unit>()
        var secondFinished = false

        val first = launch {
            ops.runStaged(successMessage = "s1", consentBudget = 1) {
                firstStarted.complete(Unit)
                MediaOpResult.NeedsConsent(stubIntentSender(), ConsentKind.Write)
            }
        }
        firstStarted.await()
        runCurrent() // 确保已经挂进授权等待

        val second = launch {
            ops.runStaged(successMessage = "s2", consentBudget = 1) {
                secondFinished = true
                MediaOpResult.Success
            }
        }
        first.cancel()
        first.join()
        second.join()
        assertTrue(secondFinished)
    }

    @Test
    fun `no consent subscriber reports denied instead of hanging`() = runTest {
        // SharedFlow(replay=0) 在无订阅者时 tryEmit 恒返回 true（值被丢弃）——
        // 拿它的返回值判定「没人订阅」的话，这里会永久悬挂且 gate 不释放。
        // 正确行为：按订阅数判定，立即按「未授权」收口。
        //
        // ⚠️ 只断言 failureMsg 钉不住这条：就算判据失效（协程挂死在等待上），
        // runTest 的虚拟时钟也会立刻把 5 分钟兜底超时走完、产出同一条
        // consent_denied 文案。所以必须同时断言「没有消耗任何虚拟时间」——
        // 超时兜底一介入，currentTime 就会是 300_000 而不是 0。
        val ops = MediaOpCoordinator(strings)
        var failureMsg: String? = null
        ops.runStaged(
            successMessage = "done",
            onFailure = { failureMsg = it },
            consentBudget = 1,
        ) { MediaOpResult.NeedsConsent(stubIntentSender(), ConsentKind.Write) }
        assertEquals("res:${R.string.media_op_consent_denied}", failureMsg)
        assertEquals("不应靠超时兜底收口", 0L, testScheduler.currentTime)
    }

    @Test
    fun `resumeConsent delivers the user's answer to the right waiter`() = runTest {
        val ops = MediaOpCoordinator(strings)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ops.consent.collect {}
        }
        val sender = stubIntentSender()

        var firstMsg: String? = null
        val first = launch {
            ops.runStaged(
                successMessage = "s1",
                onFailure = { firstMsg = it },
                consentBudget = 1,
            ) { MediaOpResult.NeedsConsent(sender, ConsentKind.Write) }
        }
        runCurrent() // first 已在等待授权
        ops.resumeConsent(false) // 用户拒绝 → 第一个按「未授权」收口
        first.join()
        assertEquals("res:${R.string.media_op_consent_denied}", firstMsg)

        var secondSucceeded = false
        val second = launch {
            ops.runStaged(
                successMessage = "s2",
                onSuccess = { secondSucceeded = true },
                consentBudget = 1,
            ) { attempt ->
                if (attempt == 0) MediaOpResult.NeedsConsent(sender, ConsentKind.Write)
                else MediaOpResult.Success
            }
        }
        runCurrent() // second 已在等待授权
        ops.resumeConsent(true) // 用户同意 → 重放（attempt=1）→ 成功
        second.join()
        assertTrue(secondSucceeded)
    }
}
