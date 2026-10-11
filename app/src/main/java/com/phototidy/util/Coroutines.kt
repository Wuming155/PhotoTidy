package com.phototidy.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] 的 suspend 变体，但**不**捕获 [CancellationException]。
 *
 * 取消是结构化并发的流程控制，不是失败：把它吞成 `Result.failure` 会让
 * 「刷新被取消」冒充成「读取失败」弹给用户。其余异常照旧包装。
 */
suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
