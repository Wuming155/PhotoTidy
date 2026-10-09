package com.phototidy.core

import android.content.res.Resources

/**
 * 本地化文案的唯一出口。
 *
 * 把「拿字符串」抽象成接口，是为了让 [com.phototidy.ui.review.ReviewViewModel]
 * 这类纯逻辑组件能脱离 `android.content.res.Resources` 在 JVM 单测里跑 ——
 * 测试里注入一个返回固定文案的假实现即可，不必上 Robolectric。
 */
interface StringProvider {
    /** 等价于 `Resources.getString(resId, *args)`。 */
    fun get(resId: Int, vararg args: Any): String
}

/** 生产环境实现：直接转发到 `Resources`。 */
class ResourcesStringProvider(private val resources: Resources) : StringProvider {
    override fun get(resId: Int, vararg args: Any): String =
        resources.getString(resId, *args)
}
