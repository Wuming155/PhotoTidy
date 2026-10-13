package com.phototidy

import android.app.Application
import android.content.Context
import android.content.res.Resources
import com.phototidy.core.StringProvider
import com.phototidy.core.ResourcesStringProvider
import com.phototidy.core.media.DuplicateFinder
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaStoreRepository
import com.phototidy.core.media.RealSessionStore
import com.phototidy.core.media.ReviewedLedger
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

    /**
     * 「已整理」账本。
     *
     * 必须在概览扫描（[repository]）与整理会话（ReviewViewModel）之间共享同一份实例 ——
     * 否则「刚整理完一张」不会立刻反映到首页数字上。
     */
    val reviewedLedger: ReviewedLedger by lazy { ReviewedLedger(sessionStore) }

    val mediaOps: MediaOpCoordinator by lazy { MediaOpCoordinator(stringProvider) }

    /**
     * 重复照片比对。
     *
     * 纯本地实现（见 [DuplicateFinder]）：先按文件大小粗筛、再对候选算 dHash 指纹。
     * 需要 Context 是因为要解码缩略图，但**不碰网络** —— 应用清单里没有网络权限。
     */
    val duplicateFinder: DuplicateFinder by lazy { DuplicateFinder(appContext, repository) }

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
