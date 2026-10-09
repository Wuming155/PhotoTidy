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
 *    而不是让协程永远等下去。
 *
 * 失败文案走 [StringProvider]（`core/` 里不留任何字面量），
 * 这样这个类仍然可以在 JVM 单测里用假实现跑起来。
 */
class MediaOpCoordinator(private val strings: StringProvider) {

    private val _consent = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val consent: SharedFlow<IntentSenderRequest> = _consent.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 正在等用户做选择的那一次授权。同一时刻只允许一个。 */
    private var waiting: CancellableContinuation<Boolean>? = null

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
     */
    suspend fun runStaged(
        successMessage: String,
        onSuccess: (suspend () -> Unit)? = null,
        onFailure: (suspend (String) -> Unit)? = null,
        consentBudget: Int,
        op: suspend (attempt: Int) -> MediaOpResult,
    ) = execute(successMessage, onSuccess, onFailure, attempt = 0, budget = consentBudget, op = op)

    private suspend fun execute(
        successMessage: String,
        onSuccess: (suspend () -> Unit)?,
        onFailure: (suspend (String) -> Unit)?,
        attempt: Int,
        budget: Int,
        op: suspend (Int) -> MediaOpResult,
    ) {
        when (val result = op(attempt)) {
            is MediaOpResult.Success -> {
                onSuccess?.invoke()
                emit(successMessage)
            }

            is MediaOpResult.Failed -> report(onFailure, result.message)

            is MediaOpResult.NeedsConsent -> when {
                attempt >= budget -> report(
                    onFailure,
                    strings.get(R.string.media_op_budget_exhausted),
                )

                awaitConsent(result.intentSender) ->
                    execute(successMessage, onSuccess, onFailure, attempt + 1, budget, op)

                else -> report(onFailure, strings.get(R.string.media_op_consent_denied))
            }
        }
    }

    private suspend fun report(onFailure: (suspend (String) -> Unit)?, message: String) {
        onFailure?.invoke(message)
        emit(message)
    }

    /** 挂起直到用户在系统框上做出选择；没有订阅者时立即返回 false。 */
    private suspend fun awaitConsent(sender: IntentSender): Boolean =
        suspendCancellableCoroutine { continuation ->
            // 正常路径下 execute() 会等 awaitConsent 完全返回后才递归，所以走到这里时 waiting 必为 null。
            // 但若将来调用方并发发起授权（例如单会话 UI 同时触发两次），cancel 会直接取消上一个等待协程，
            // 而它整条调用链没有 onFailure 通知 —— 调用方可能收不到「被跳过」的信号。
            // 这是已知隐患，单会话 UI 当前触发不到，留此警示。
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
}
