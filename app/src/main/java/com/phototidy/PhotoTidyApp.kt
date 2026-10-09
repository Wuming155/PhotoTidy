package com.phototidy

import android.app.Application
import android.content.Context
import android.content.res.Resources
import com.phototidy.core.StringProvider
import com.phototidy.core.ResourcesStringProvider
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaStoreRepository
import com.phototidy.core.media.RealSessionStore
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.settings.SettingsStore

/**
 * 极简依赖容器。
 *
 * 这个应用只有五个长生命周期依赖（设置 / 媒体仓库 / 暂存回收站 / 会话快照 / 写操作协调器），
 * 引入 Hilt 或 Koin 带来的构建时间、注解处理和心智负担都不划算。
 * 这里用显式单例 + `by lazy`：编译期可查、崩溃栈可读。
 * 真到了 3 个以上模块互有依赖的时候再迁移。
 */
object Graph {

    private lateinit var appContext: Context

    val context: Context get() = appContext

    /** ViewModel 里拼提示语要用 —— 它们没有 Context，但需要拿到本地化后的文案。 */
    val resources: Resources get() = appContext.resources

    /** 本地化文案的唯一出口；`core/` 里的类也通过它拿文案。 */
    val stringProvider: StringProvider by lazy { ResourcesStringProvider(appContext.resources) }

    val repository: MediaStoreRepository by lazy { MediaStoreRepository(appContext, stringProvider) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val stagingTrash: StagingTrash by lazy { StagingTrash() }

    val sessionStore: SessionStore by lazy { RealSessionStore(appContext) }

    val mediaOps: MediaOpCoordinator by lazy { MediaOpCoordinator(stringProvider) }

    fun install(context: Context) {
        appContext = context.applicationContext
    }
}

class PhotoTidyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.install(this)
    }
}
