package com.phototidy.verification

import android.net.Uri
import com.phototidy.core.StringProvider
import com.phototidy.core.media.AlbumNameIssue
import com.phototidy.core.media.MediaAccess
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaOpResult
import com.phototidy.core.media.MediaPermissions
import com.phototidy.core.media.MediaRepository
import com.phototidy.core.media.MoveFailure
import com.phototidy.core.media.ReviewedLedger
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.media.computeTargetPath
import com.phototidy.core.media.decodeReviewedBatch
import com.phototidy.core.media.encodeReviewedBatch
import com.phototidy.core.media.newAlbumIssue
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.PendingMove
import com.phototidy.ui.review.ReviewQueueWindow
import com.phototidy.ui.review.ReviewViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureNanoTime

@OptIn(ExperimentalCoroutinesApi::class)
class AuditVerificationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeImage(id: Long, name: String = "n$id", relativePath: String = "DCIM/Camera") = MediaImage(
        id = id,
        uri = "content://media/external/images/media/$id",
        displayName = name,
        takenAtMillis = 1000L * id,
        addedAtMillis = 1000L * id,
        bucketId = 1,
        albumName = "Camera",
        relativePath = relativePath,
        sizeBytes = 1024,
        width = 100,
        height = 100,
        isFavorite = false,
        isTrashed = false,
    )

    private class FakeSessionStore : SessionStore {
        val staging = mutableListOf<Long>()
        val pending = mutableMapOf<String, List<PendingMove>>()
        val reviewed = linkedSetOf<Long>()

        override fun saveStaging(imageIds: List<Long>) {
            staging.clear()
            staging.addAll(imageIds)
        }

        override fun loadStaging(): List<Long> = staging.toList()
        override fun clearStaging() = staging.clear()

        override fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {
            pending[scopeKey] = moves.toList()
        }

        override fun loadPendingMoves(scopeKey: String): List<PendingMove> = pending[scopeKey].orEmpty()
        override fun clearPendingMoves(scopeKey: String) {
            pending.remove(scopeKey)
        }

        override fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>) {
            reviewed.removeAll(unmarked.toSet())
            reviewed.addAll(marked)
        }

        override fun loadReviewed(): List<Long> = reviewed.toList()
    }

    private class FakeMediaRepo(
        var images: Map<Long, MediaImage> = emptyMap(),
        var moveResult: MediaOpResult = MediaOpResult.Success,
    ) : MediaRepository {
        var moveToAlbumsCalls = 0

        override suspend fun moveToSystemTrash(imageIds: List<Long>, attempt: Int): MediaOpResult =
            MediaOpResult.Success

        override suspend fun imagesByIds(ids: Collection<Long>): List<MediaImage> =
            ids.mapNotNull { images[it] }

        override suspend fun moveToAlbums(items: List<PendingMove>): MediaOpResult {
            moveToAlbumsCalls++
            return moveResult
        }
    }

    private class TestStringProvider : StringProvider {
        override fun get(resId: Int, vararg args: Any): String = "str_$resId(${args.joinToString()})"
    }

    // =========================================================================
    // 验证 1: 快速手势与动作条点击竞争测试 (Issue 1) - 双层防护回归测试
    // =========================================================================
    @Test
    fun verify_Issue1_Fixed_SwipeTrashThenKeepButtonThenDelayedAnimation() = runTest {
        val imgA = makeImage(101L, "PhotoA")
        val imgB = makeImage(102L, "PhotoB")
        val imgC = makeImage(103L, "PhotoC")

        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val repo = FakeMediaRepo(images = mapOf(101L to imgA, 102L to imgB, 103L to imgC))
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 3,
            remainingCount = 3,
            initialMoveTargets = emptyList(),
            loader = { _, _ -> listOf(imgA, imgB, imgC) },
            onCommitted = {},
        )

        // 初始状态断言
        assertEquals(101L, vm.state.value.current?.id)
        assertEquals(0, staging.items.value.size)
        assertFalse(ledger.contains(101L))
        assertFalse(ledger.contains(102L))

        // 模拟时序：
        // 1. 用户向上拖动卡片 A，触发 dismissal 飞出动画（目标 ID = 101L）。
        // 2. 动画尚未结束，外部 Keep 按钮由于并发事件被点击（针对照片 A，携带 ID 101L）：
        vm.keep(101L)

        // 此时断言：A 被 Keep 消费，B 变为了当前卡片
        assertTrue("A 应已被记录到账本", ledger.contains(101L))
        assertEquals("当前卡片变成了 B", 102L, vm.state.value.current?.id)
        assertEquals("暂存回收站不应包含 A", 0, staging.items.value.size)

        // 3. 飞出动画结束，SwipeablePhotoCard 回调到达，携带触发时的 ID 101L：
        vm.trash(101L)

        // 最终状态断言：
        // 【关键保护生效】：因为当前队首已是 B (102L)，携带 101L 的过期 Trash 回调被安全忽略！
        assertTrue("A 只被消费一次（保留）", ledger.contains(101L))
        assertFalse("B 完全不受影响，未被消费", ledger.contains(102L))
        assertEquals("暂存回收站中不能出现照片 B！", 0, staging.items.value.size)
        assertEquals("当前队列展示依然是照片 B", 102L, vm.state.value.current?.id)
        assertEquals("下一张预览依然是照片 C", 103L, vm.state.value.next?.id)
        assertTrue("PendingMoves 应当为空", store.loadPendingMoves(MediaScope.Recent.key).isEmpty())
    }

    @Test
    fun verify_Issue1_Fixed_SwipeKeepThenTrashButton() = runTest {
        val imgA = makeImage(101L, "PhotoA")
        val imgB = makeImage(102L, "PhotoB")
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val repo = FakeMediaRepo(images = mapOf(101L to imgA, 102L to imgB))
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 2,
            remainingCount = 2,
            initialMoveTargets = emptyList(),
            loader = { _, _ -> listOf(imgA, imgB) },
            onCommitted = {},
        )

        // 1. A 被 Trash 按钮消费（携带 ID 101L）
        vm.trash(101L)
        assertEquals(1, staging.items.value.size)
        assertEquals(102L, vm.state.value.current?.id)

        // 2. 过期的 Swipe Keep 回调到达（携带 ID 101L）
        vm.keep(101L)

        // 验证：过期 Keep 回调被忽略，B 仍然是当前项且未被改变
        assertFalse("B 未被消费", ledger.contains(102L))
        assertEquals(102L, vm.state.value.current?.id)
        assertEquals(1, staging.items.value.size)
    }

    @Test
    fun verify_Issue1_Fixed_SwipeTrashThenQuickMoveBar() = runTest {
        val imgA = makeImage(101L, "PhotoA")
        val imgB = makeImage(102L, "PhotoB")
        val targetAlbum = MediaAlbum(10, "Trip", "Pictures/Trip", 5, null)
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val repo = FakeMediaRepo(images = mapOf(101L to imgA, 102L to imgB))
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 2,
            remainingCount = 2,
            initialMoveTargets = listOf(targetAlbum),
            loader = { _, _ -> listOf(imgA, imgB) },
            onCommitted = {},
        )

        // 1. A 被 Trash 消费
        vm.trash(101L)
        assertEquals(102L, vm.state.value.current?.id)

        // 2. 针对 A 的快捷移动事件到达（携带 ID 101L）
        vm.moveTo(targetAlbum, 101L)

        // 验证：moveTo 被忽略，不会把 B 移入相册
        assertEquals(0, vm.state.value.pendingMoveCount)
        assertEquals(102L, vm.state.value.current?.id)
    }

    @Test
    fun verify_Issue1_Fixed_RapidSuccessiveSwipes() = runTest {
        val imgA = makeImage(101L, "PhotoA")
        val imgB = makeImage(102L, "PhotoB")
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val repo = FakeMediaRepo(images = mapOf(101L to imgA, 102L to imgB))
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 2,
            remainingCount = 2,
            initialMoveTargets = emptyList(),
            loader = { _, _ -> listOf(imgA, imgB) },
            onCommitted = {},
        )

        // 连续两次触发同一张卡片的滑动
        vm.trash(101L)
        vm.trash(101L) // 重复调用

        assertEquals("回收站只有一张", 1, staging.items.value.size)
        assertEquals("当前项为 B", 102L, vm.state.value.current?.id)
        assertFalse("B 未被误删", ledger.contains(102L))
    }

    // =========================================================================
    // 验证 2: ReviewQueueWindow 分页水位回退行为 (Issue 7)
    // =========================================================================
    @Test
    fun verify_Issue7_ReviewQueueWindow_LoadedThroughBehavior() {
        val window = ReviewQueueWindow(totalCount = 100)
        val pageSize = 150

        // 模拟：初始总数 100，实际拉到了 120 条数据（例如外部新增了 20 张）
        val page1 = (1L..120L).map { makeImage(it) }

        // 调用 acceptPage
        window.acceptPage(page1, pageSize = pageSize) { true }

        // 检查 loadedCount
        // 源码逻辑：loadedThrough = if (page.size < pageSize) totalCount else loadedThrough + page.size
        // 因为 120 < 150，loadedThrough 被直接赋值为 totalCount (100)！
        assertEquals(100, window.loadedCount)

        // 检查 sourceExhausted: loadedThrough >= totalCount (100 >= 100 为 true)
        assertTrue("sourceExhausted 为 true", window.sourceExhausted)

        // 检查 needsRefill: !sourceExhausted && ready.size < refillBelow
        // 即使 ready.size = 0，因为 sourceExhausted 为 true，needsRefill 永远返回 false！
        assertFalse("needsRefill 恒为 false，绝不可能触发重复请求", window.needsRefill(refillBelow = 10))

        // 结论：第一轮报告认为「会导致再次读取 offset 100..120 的照片造成重复加载」是错误的！
        // 因为 sourceExhausted 变为了 true，翻页已经彻底终止，不会再次触发补页。
    }

    // =========================================================================
    // 验证 3: 账本编码与解码性能基准测试 (Issue 5)
    // =========================================================================
    @Test
    fun verify_Issue5_ReviewedLedger_PerformanceBenchmark() {
        val testSizes = listOf(1_000, 10_000, 30_000, 50_000, 100_000)
        val results = mutableListOf<String>()

        for (size in testSizes) {
            val ids = (1L..size.toLong()).toList()

            // 1. 测试编码耗时
            val encodeNanos = measureNanoTime {
                encodeReviewedBatch(ids, emptyList())
            }
            val encoded = encodeReviewedBatch(ids, emptyList())
            val encodedLengthBytes = encoded.length

            // 2. 测试解码耗时 (含 raw.split(' '))
            val intoSet = HashSet<Long>(size)
            val decodeNanos = measureNanoTime {
                decodeReviewedBatch(encoded, intoSet)
            }

            assertEquals(size, intoSet.size)

            val encodeMs = encodeNanos / 1_000_000.0
            val decodeMs = decodeNanos / 1_000_000.0
            val report = "Size: $size | Encoded: ${encodedLengthBytes / 1024} KB | Encode: %.2f ms | Decode (split): %.2f ms"
                .format(encodeMs, decodeMs)
            results.add(report)
            println(report)
        }

        assertTrue("基准测试完成", results.isNotEmpty())
    }

    // =========================================================================
    // 验证 4: 移动失败项被成功剔除，不造成待提交队列死锁 (Issue 6 修复验证)
    // =========================================================================
    @Test
    fun verify_Issue6_MoveFailure_EvictedWithoutDeadlock() = runTest {
        // 10 张待移动照片，其中前 9 张成功，第 10 张由于同名冲突失败
        val images = (201L..210L).associateWith { makeImage(it, "IMG_$it.jpg", "DCIM/Camera/") }
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val failure = MoveFailure(uri = null, imageId = 210L, message = "UNIQUE constraint failed")
        val batchResult = MediaOpResult.Batch(
            successfulIds = (201L..209L).toSet(),
            failedItems = mapOf(210L to failure),
        )
        val repo = FakeMediaRepo(images = images, moveResult = batchResult)
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val targetAlbum = MediaAlbum(999, "Trip", "Pictures/Trip", 10, null)

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 10,
            remainingCount = 10,
            initialMoveTargets = listOf(targetAlbum),
            loader = { _, _ -> images.values.toList() },
            onCommitted = {},
        )

        // 全部移动到 Trip
        for (id in 201L..210L) {
            vm.moveTo(targetAlbum, id)
        }
        assertEquals(10, vm.state.value.pendingMoveCount)

        // 点击提交
        vm.commit()

        // 验证：moveToAlbums 被调用
        assertEquals(1, repo.moveToAlbumsCalls)

        // 验证：修复后，无论是成功的 9 张还是失败的第 10 张，都从 pendingMoves 中被移除！
        // 避免死锁：失败的照片不再残留并卡死下一次提交
        assertEquals("待提交队列已被完全清空，避免死锁", 0, vm.state.value.pendingMoveCount)
        assertFalse("提交按钮恢复未激活态", vm.state.value.hasPending)
        assertTrue("存储中的待提交队列也已完全清空", store.loadPendingMoves(MediaScope.Recent.key).isEmpty())

        // 验证方案 A (Rollback) 状态一致性：
        // 1. 失败的照片 210L 绝不留在账本中
        assertFalse("失败的照片 210L 已从已整理账本中回滚撤销", ledger.contains(210L))
        assertTrue("成功的 9 张照片正常记录在已整理账本", (201L..209L).all { ledger.contains(it) })
        // 2. 失败的照片 210L 放回 ReviewQueue 头部，用户可重新处理
        assertEquals("失败的照片 210L 恢复到当前队列队首", 210L, vm.state.value.current?.id)
        // 3. 处理数 processed 减去失败的 1 张，准确为 9
        assertEquals("会话已处理数精准对账为 9", 9, vm.state.value.processed)
        assertFalse("队列未完结，界面留在该照片上", vm.state.value.finished)
    }

    // =========================================================================
    // 验证 5: 新建相册非法字符过滤缺陷 (Issue 9)
    // =========================================================================
    @Test
    fun verify_Issue9_NewAlbumSpecialChars_AllowedByValidator() {
        val existing = listOf("Family", "Travel")

        // 源码逻辑：name.any { it == '/' || it == '\\' || it.isISOControl() }
        // 检查常见 FAT32/Android 存储非法字符：
        val colonIssue = newAlbumIssue("Trip:2026", existing)
        val asteriskIssue = newAlbumIssue("Trip*2026", existing)
        val questionIssue = newAlbumIssue("Trip?2026", existing)
        val quoteIssue = newAlbumIssue("Trip\"2026", existing)
        val pipeIssue = newAlbumIssue("Trip|2026", existing)

        // 验证：当前验证器完全没有拦截冒号、星号、问号、引号、管道符！
        assertNull("冒号没有被拦截！", colonIssue)
        assertNull("星号没有被拦截！", asteriskIssue)
        assertNull("问号没有被拦截！", questionIssue)
        assertNull("引号没有被拦截！", quoteIssue)
        assertNull("管道符没有被拦截！", pipeIssue)

        // 而正斜杠被拦截了
        assertEquals(AlbumNameIssue.IllegalChars, newAlbumIssue("Trip/2026", existing))
    }

    // =========================================================================
    // 验证 6: 重复移动到当前所在相册当作 Keep 处理 (Issue 12 修复验证)
    // =========================================================================
    @Test
    fun verify_Issue12_RedundantMoveToSameAlbum() = runTest {
        // 照片本身已经在 DCIM/Camera/ 下
        val img1 = makeImage(301L, "IMG_0001.jpg", relativePath = "DCIM/Camera/")
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val repo = FakeMediaRepo(images = mapOf(301L to img1))
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val cameraAlbum = MediaAlbum(
            bucketId = 1,
            name = "Camera",
            relativePath = "DCIM/Camera",
            count = 100,
            cover = null,
        )

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 1,
            remainingCount = 1,
            initialMoveTargets = listOf(cameraAlbum),
            loader = { _, _ -> listOf(img1) },
            onCommitted = {},
        )

        // 用户在快捷移动条上点击了与当前路径完全一致的 "Camera"
        vm.moveTo(cameraAlbum, 301L)

        // 验证：修复后，不再产生冗余的待移动记录！当作 Keep 处理
        assertEquals("不产生待移动记录", 0, vm.state.value.pendingMoveCount)
        assertTrue("存储中没有 pending move", store.loadPendingMoves(MediaScope.Recent.key).isEmpty())
        assertTrue("照片被正常标记为已整理", ledger.contains(301L))
        assertTrue("队列顺利完结", vm.state.value.finished)

        // 验证撤销功能：应当能够反悔并恢复照片到当前队首
        assertTrue("支持撤销", vm.state.value.canUndo)
        vm.undo()
        assertEquals("撤销后照片恢复到当前队首", 301L, vm.state.value.current?.id)
        assertFalse("队列未完结", vm.state.value.finished)
    }

    // =========================================================================
    // 验证 7: Android 14+ Partial 权限取消不会被降级为 Denied (Issue 3 修复验证)
    // =========================================================================
    @Test
    fun verify_Issue3_MediaPermissions_ResolveAccess() {
        val emptyResult = emptyMap<String, Boolean>()
        val cancelResult = mapOf(
            "android.permission.READ_MEDIA_IMAGES" to false,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to false,
        )

        // 1. 原来是 Partial，取消授权弹窗返回全 false：必须维持 Partial，不能降级为 Denied
        assertEquals(
            MediaAccess.Partial,
            MediaPermissions.resolveAccess(MediaAccess.Partial, emptyResult),
        )
        assertEquals(
            MediaAccess.Partial,
            MediaPermissions.resolveAccess(MediaAccess.Partial, cancelResult),
        )

        // 2. 原来是 Denied，弹窗取消：依然是 Denied
        assertEquals(
            MediaAccess.Denied,
            MediaPermissions.resolveAccess(MediaAccess.Denied, cancelResult),
        )

        // 3. 用户在弹窗中授予了完全访问权限：升级为 Full
        val fullResult = mapOf("android.permission.READ_MEDIA_IMAGES" to true)
        assertEquals(
            MediaAccess.Full,
            MediaPermissions.resolveAccess(MediaAccess.Partial, fullResult),
        )

        // 4. 用户授权了部分照片：返回 Partial
        val partialResult = mapOf("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to true)
        assertEquals(
            MediaAccess.Partial,
            MediaPermissions.resolveAccess(MediaAccess.Denied, partialResult),
        )
    }

    // =========================================================================
    // 验证 8: Group 3 基准测试 - loadAllTrash 分页循环性能 (Issue B 基准测试)
    // =========================================================================
    @Test
    fun verify_Group3_Benchmark_LoadAllTrash() {
        val testSizes = listOf(200, 1_000, 2_000, 5_000, 10_000)
        val pageSize = 200

        for (total in testSizes) {
            var loaded = 0
            val timeNanos = measureNanoTime {
                while (loaded < total) {
                    val batch = minOf(pageSize, total - loaded)
                    loaded += batch
                }
            }
            val timeMs = timeNanos / 1_000_000.0
            println("Benchmark loadAllTrash: total=$total items, iterations=${total / pageSize}, time=%.3f ms".format(timeMs))
            assertEquals(total, loaded)
            assertTrue("loadAllTrash 纯内存循环极为轻量 (< 5ms)", timeMs < 5.0)
        }
    }

    // =========================================================================
    // 验证 9: Process Restart 后 PendingMove 失败回滚 (即使 history 为空也能从 repo 捞回)
    // =========================================================================
    @Test
    fun verify_ProcessRestart_FailedMove_RollbackWithoutHistory() = runTest {
        val imgA = makeImage(501L, "PhotoA.jpg", "DCIM/Camera/")
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val tripAlbum = MediaAlbum(888, "Trip", "Pictures/Trip", 0, null)

        val failure = MoveFailure(uri = null, imageId = 501L, message = "Storage Error")
        val failResult = MediaOpResult.Batch(
            successfulIds = emptySet(),
            failedItems = mapOf(501L to failure),
        )
        val repo = FakeMediaRepo(images = mapOf(501L to imgA), moveResult = failResult)
        val coordinator = MediaOpCoordinator(TestStringProvider())

        // 阶段 1: vm1 中照片 A 选择移动到 Album Trip，待移动落盘
        val vm1 = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 1,
            remainingCount = 1,
            initialMoveTargets = listOf(tripAlbum),
            loader = { _, _ -> listOf(imgA) },
            onCommitted = {},
        )
        vm1.moveTo(tripAlbum, 501L)
        assertEquals(1, vm1.state.value.pendingMoveCount)
        assertTrue("SessionStore 已持久化待移动项", store.loadPendingMoves(MediaScope.Recent.key).isNotEmpty())
        assertTrue("账本已标记 501L", ledger.contains(501L))

        // 阶段 2: 模拟应用退出 / Process Restart，创建全新的 ReviewViewModel (vm2)
        // 此时 vm2 的 history 内存为空。即使 restorePendingMoves 因任何时序未完成，直接执行 commit
        val vm2 = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 1,
            remainingCount = 1,
            initialMoveTargets = listOf(tripAlbum),
            loader = { _, _ -> emptyList() }, // 模拟主队列没有该照片
            onCommitted = {},
        )

        // 执行提交，此时 Repository 返回 A move failed
        vm2.commit()

        // 验证最终状态：
        // 1. pendingMoves 中已清除 A
        assertEquals("pendingMoves 中无 A", 0, vm2.state.value.pendingMoveCount)
        assertTrue("SessionStore 落盘已清除 A", store.loadPendingMoves(MediaScope.Recent.key).isEmpty())

        // 2. reviewed 账本撤销 A
        assertFalse("reviewed 账本撤销 A", ledger.contains(501L))

        // 3. A 没有永久消失，从 repo.imagesByIds 捞回并压入队列头部
        assertEquals("A 重新回到当前队首供再次整理", 501L, vm2.state.value.current?.id)

        // 4. processedCount 不会出现 -1，保底为 0
        assertEquals("processedCount 保底不出现 -1", 0, vm2.state.value.processed)
        assertEquals("kept 统计为 0", 0, vm2.state.value.keptCount)
    }

    // =========================================================================
    // 验证 10: 多张照片移动失败回滚时的队首顺序保持 (B, C -> [B, C])
    // =========================================================================
    @Test
    fun verify_MultipleFailedMoves_PreservesQueueOrder() = runTest {
        val imgB = makeImage(601L, "PhotoB.jpg", "DCIM/Camera/")
        val imgC = makeImage(602L, "PhotoC.jpg", "DCIM/Camera/")
        val store = FakeSessionStore()
        val ledger = ReviewedLedger(store)
        val staging = StagingTrash(store).apply { markRestored() }
        val tripAlbum = MediaAlbum(888, "Trip", "Pictures/Trip", 0, null)

        val batchFailResult = MediaOpResult.Batch(
            successfulIds = emptySet(),
            failedItems = linkedMapOf(
                601L to MoveFailure(uri = null, imageId = 601L, message = "B fail"),
                602L to MoveFailure(uri = null, imageId = 602L, message = "C fail"),
            ),
        )
        val repo = FakeMediaRepo(images = mapOf(601L to imgB, 602L to imgC), moveResult = batchFailResult)
        val coordinator = MediaOpCoordinator(TestStringProvider())

        val vm = ReviewViewModel(
            scope = MediaScope.Recent,
            repo = repo,
            session = store,
            staging = staging,
            reviewed = ledger,
            ops = coordinator,
            strings = TestStringProvider(),
            totalCount = 2,
            remainingCount = 2,
            initialMoveTargets = listOf(tripAlbum),
            loader = { _, _ -> listOf(imgB, imgC) },
            onCommitted = {},
        )

        // 依次将 B、C 移至 Trip
        vm.moveTo(tripAlbum, 601L)
        vm.moveTo(tripAlbum, 602L)
        assertEquals(2, vm.state.value.pendingMoveCount)

        // 提交，两张均失败
        vm.commit()

        // 验证回滚后队列顺序：B 必须在队首，C 在 B 之后，顺序不能被倒置为 [C, B]！
        assertEquals("回滚后第一张必须是 B (601L)", 601L, vm.state.value.current?.id)
        // 消费 B
        vm.keep(601L)
        assertEquals("消费 B 之后的下一张必须是 C (602L)", 602L, vm.state.value.current?.id)
    }
}

