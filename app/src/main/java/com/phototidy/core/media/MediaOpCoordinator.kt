package com.phototidy.core.media

import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import com.phototidy.R
import com.phototidy.core.StringProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 媒体写操作的统一协调器。
 *
 * 职责：把「系统拒绝了这次写入」变成一次可等待的用户授权流程 ——
 * 写入被拒 → 弹系统授权框 → **挂起当前协程** → 用户在框上做选择 → 恢复并重放操作。
 *
 * 为什么是「挂起」而不是「回调」：
 * 一次整理会话提交时往往要连续做几类操作（先移动、再进回收站）。
 * 回调式实现下，第二类操作会在第一类还没拿到结果时就发出请求，
 * 既会互相覆盖挂起状态，也会连着弹出好几个系统框 —— 而这正是「每改一张就提醒一次」的根源。
 *
 * 两条不可动摇的约束：
 *
 * 1. **授权次数必须有上限**（由 [run] 的 `consentBudget` 控制，默认 1）。
 *    授权回来后重放，如果次数已用尽仍被拒就判定失败、不再弹框。否则「弹框 → 系统立即返回 OK → 重放 → 又被拒 → 再弹框」会形成死循环：
 *    实机实测它以约 130ms 一轮的频率持续写 MediaStore，十几秒就触发系统的
 *    rapidActivityLaunch 保护，把 MediaProvider 连同本应用一起杀掉。
 *
 * 2. **没有订阅者时绝不悬挂**。UI 还没订阅 [consent] 流时直接判定「未授权」返回，
 *    而不是让协程永远等下去。判定依据是**订阅数**而不是 `tryEmit` 的返回值 ——
 *    后者在无订阅者时恒为 true（值被静默丢弃），拿它判定会把「永远等不到结果」
 *    伪装成「已送达」（见 [awaitConsent]）。等待授权本身还有兜底超时：
 *    握着 [gate] 的协程必须保证最终走完，否则整个应用的媒体写操作都会被堵死。
 *
 * 失败文案走 [StringProvider]（`core/` 里不留任何字面量），
 * 这样这个类仍然可以在 JVM 单测里用假实现跑起来。
 */
class MediaOpCoordinator(private val strings: StringProvider) {

    private val _consent = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val consent: SharedFlow<IntentSenderRequest> = _consent.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 正在等用户做选择的那一次授权。同一时刻只允许一个 —— 由 [gate] 保证。 */
    private var waiting: CancellableContinuation<Boolean>? = null

    /**
     * 一次只放一个写操作进来，直到它的授权流程走完（含等用户在系统框上做选择）。
     *
     * 协调器是进程级单例，整理会话的提交与回收站页的还原/删除共用它。
     * 没有这道闸时，两路并发触发授权会互相覆盖 [waiting]：后到者把先到者
     * `cancel()` 掉，而那条被取消的调用链拿不到任何「被跳过」的通知。
     * 串行化之后并发触发只会排队 —— 先到者把授权走完，后到者再开始。
     */
    private val gate = Mutex()

    /**
     * 执行一次媒体写操作，必要时等用户授权。
     *
     * [op] 必须是幂等的 —— 拿到授权后会被原样重放一次。
     */
    suspend fun run(
        successMessage: String,
        onSuccess: (suspend () -> Unit)? = null,
        onFailure: (suspend (String) -> Unit)? = null,
        op: suspend () -> MediaOpResult,
    ) = runStaged(successMessage, onSuccess, onFailure, consentBudget = 1) { op() }

    /**
     * 同 [run]，但允许一次操作最多向系统要 [consentBudget] 次授权。
     *
     * 一次会话提交里如果同时有「移动」和「进回收站」，系统可能要求两种不同的授权
     * （改路径是一种、进回收站是另一种），此时传 2。其余场景保持 1 即可 ——
     * 这个上限的意义是堵死「弹框 → 立即返回 OK → 重放 → 又被拒 → 再弹框」的死循环，
     * 而不是限制正常的分类授权。
     *
     * [op] 会收到「这是第几次尝试」（从 0 起），便于上层区分
     * 「还没拿到任何授权」和「已经拿到写授权」。
     *
     * 整个流程（含等待授权的挂起期）持有 [gate]，并发调用会在此排队；
     * 重试用循环而不是递归 —— Mutex 不可重入，递归重试会在重入时死锁。
     */
    suspend fun runStaged(
        successMessage: String,
        onSuccess: (suspend () -> Unit)? = null,
        onFailure: (suspend (String) -> Unit)? = null,
        consentBudget: Int,
        op: suspend (attempt: Int) -> MediaOpResult,
    ) {
        gate.withLock {
            var attempt = 0
            while (true) {
                when (val result = op(attempt)) {
                    is MediaOpResult.Success -> {
                        onSuccess?.invoke()
                        emit(successMessage)
                        return
                    }

                    is MediaOpResult.Failed -> {
                        report(onFailure, result.message)
                        return
                    }

                    is MediaOpResult.NeedsConsent -> {
                        if (attempt >= consentBudget) {
                            report(
                                onFailure,
                                strings.get(R.string.media_op_budget_exhausted),
                            )
                            return
                        }
                        // 兜底超时：授权框是系统 UI，万一订阅者中途挂掉、结果永远不回来，
                        // 不能让 gate 被永远占着 —— 超时按「未授权」收口，闸照常释放。
                        // 时间给得远超任何真实用户的阅读/犹豫时长。
                        val granted = withTimeoutOrNull(CONSENT_TIMEOUT_MILLIS) {
                            awaitConsent(result.intentSender)
                        } ?: false
                        if (granted) {
                            attempt += 1
                        } else {
                            report(onFailure, strings.get(R.string.media_op_consent_denied))
                            return
                        }
                    }
                }
            }
        }
    }

    private suspend fun report(onFailure: (suspend (String) -> Unit)?, message: String) {
        onFailure?.invoke(message)
        emit(message)
    }

    /**
     * 挂起直到用户在系统框上做出选择；没有订阅者时立即返回 false。
     */
    private suspend fun awaitConsent(sender: IntentSender): Boolean =
        suspendCancellableCoroutine { continuation ->
            // 「没人订阅」的真实判据是订阅数，**不是** tryEmit 的返回值：
            // SharedFlow 在 replay=0、无订阅者时 tryEmit 恒返回 true（值被静默丢弃），
            // 拿它判定的话这条「安全网」分支永远走不到 —— waiting 挂死，gate 也不释放。
            if (_consent.subscriptionCount.value == 0) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            // [gate] 已把授权流程串行化：走到这里时必然没有别的等待者，
            // waiting 必为 null。下面的 cancel 只是防御性兜底 —— 若未来有人
            // 绕过 gate 调到这里，宁可取消上一个等待者也不能让槽位被覆盖后泄漏。
            waiting?.cancel()
            waiting = continuation
            val delivered = _consent.tryEmit(IntentSenderRequest.Builder(sender).build())
            if (!delivered) {
                waiting = null
                continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                if (waiting === continuation) waiting = null
            }
        }

    /** 系统授权对话框关闭后调用。 */
    fun resumeConsent(granted: Boolean) {
        val continuation = waiting
        waiting = null
        if (continuation?.isActive == true) continuation.resume(granted)
    }

    fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        /** 等待用户在系统授权框上做选择的兜底上限：5 分钟，只防「结果永远不回来」。 */
        const val CONSENT_TIMEOUT_MILLIS = 5L * 60_000L
    }
}
