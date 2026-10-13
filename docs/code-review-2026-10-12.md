# PhotoTidy 全量代码审核报告

审核范围：`app/src` 下全部 46 个 Kotlin 文件（约 9,000 行）+ 清单 / 资源。
审核轴：正确性、可读性、架构、安全、性能。
审核方式：逐文件通读 + 关键路径静态推演 + JVM 探针实测（探针已删除）。

**总体结论：架构与安全模型是扎实的，但有 3 个必修缺陷，其中 1 个已用测试复现。**

> ✅ **本报告的全部 Critical / Required / Nit 已于同日在第二轮修复完毕**，见文末「修复记录」。
> 下面保留的是审核当时的原始结论（含复现证据），以便追溯。

安全模型（整理全程零媒体写入、写操作只走系统授权框、`updateAll` 检查返回值、
`createTrashRequest` 授权即执行不做二次 update）没有发现问题，这套设计经得起检查。
问题集中在**队列窗口的索引数学**、**权限状态机**、**ViewModel 构造参数的生命周期**三处。

---

## Critical（必修，其中 C1 已复现）

### C1. 「稍后再看」会把整轮整理卡死（已用 JVM 探针复现）

`ReviewViewModel.advance()` 只从 `window` 取照片：

```kotlin
private fun advance(): MediaImage? = window.removeFirstOrNull()
```

而 `at()` 会在「`window` 空 + `loadedThrough >= totalCount`」时返回 `deferred` 里的照片：

```kotlin
return if (index < tailStart) null else deferred.elementAtOrNull(index - tailStart)
```

于是存在一个状态：**`state.current` 指向一张 `deferred` 里的照片，但没有任何动作能把它取出来。**

- `keep()` / `trash()` / `moveTo()` 都是 `advance() ?: return` → 静默返回，什么都不发生；
- `finished = false`（`remainingCount()` 把 `deferred.size` 算进去了）→ 不显示完成卡片；
- `preparing = false`（`at(0) != null`）→ 也不显示转圈。

用户看到一张照片停在屏幕上，保留 / 清除 / 移动**按钮全部可点但全部无反应**，进度条永远走不完。
`restart()` 能逃出去（`deferred.clear()`），但那要求用户自己猜到。

**触发路径**：菜单「稍后再看」点一次 → 把这一范围其余照片滑完。这是常规操作，不需要任何边界条件。

**复现证据**（临时探针测试，只保留结论，文件已删）：
`totalCount = 3` 的会话里 `deferCurrent()` → `keep()` ×2 → 断言 `keep()` 应把 `processed` 从 2 推进到 3，
实际仍是 2，同时 `current` 仍是被 defer 的第 1 张、`finished = false`。

**修复方向**（任选其一，不是加个 if 就完事）：
1. `advance()` 改成「`window` 空则从 `deferred` 取」——但这样 deferred 就失去了「排到最后」的语义，
   需要在 `at()` 里同步调整，等于重写索引数学；
2. 把窗口队列抽成一个独立的 `ReviewQueueWindow` 类（`window` / `deferred` / `loadedThrough` / `peek` / `advance` /
   `remaining` 一个内聚单元），把 `at()` 的分段逻辑收进它、用单测钉死 —— **推荐**，这个类现在 0 覆盖，
   而它是整个整理会话里最容易出错的一段（`at()` 的 `windowSize + unloaded` 分段靠人脑推演）；
3. 直接删掉 `deferCurrent`（UI 上是一行菜单项）——如果这个功能不打算投入维护成本，删掉比留着好。

---

### C2. 部分授权下「选择更多照片」之后不重扫

`LibraryViewModel.onAccessResolved()`：

```kotlin
if (!loadedOnce || access != previous) { loadedOnce = true; refresh() }
```

Android 14+ 的三态模型下，用户点「选择更多照片」重新勾一批之后，`READ_MEDIA_VISUAL_USER_SELECTED` 一直是
已授予 —— `MediaPermissions.current()` 前后都返回 `Partial`，`access != previous` 为 false，
**冷启动之后再也不会有第二次扫描**。

结果：用户点了相册页 `PartialAccessBanner` 上的「选择更多」，选完回到应用，**新授权的照片一张都不出现**。
那条横幅的按钮承诺的正是这个动作，这是应用在说谎。唯一出路是首页右上角那个刷新图标。

**修复**：`Partial` 是不稳定的状态，不能只按「状态是否变化」判断。最小改动是
`if (!loadedOnce || access != previous || access.isPartial) refresh()`；
更彻底的做法是把「重新选择」当成一次显式的用户意图（`onResume` 里只要上次是 Partial 就重扫）。

---

### C3. 进程被杀后重进整理页 → 假的「整理完成」

`PhotoTidyRoot` 里 `scopeEncoded` 是 `rememberSaveable`（注释明确说这是为了「进程被系统回收后重建界面」），
但恢复路径上的分支判断有洞：

```kotlin
reviewScope != null -> {
    if (libraryState.totalCount == 0 && libraryState.loading) LoadingScreen()
    else { ... viewModel(key = "review-${reviewScope.key}", factory = ...) ... }
}
```

第一帧 `LibraryUiState` 是初始值：`totalCount = 0`、`loading = false`（`loading` 初始就是 false），
所以条件为 **false**，直接走 else —— 用 `total = 0`、`remaining = 0` 把 `ReviewViewModel` 建了出来，
并缓存进 `ViewModelStore`。

它永远不会自愈，因为：

- `totalCount` 是**构造参数**（`private val totalCount = totalCount`），`viewModel(key=...)` 之后返回的是同一个实例，
  传入的新 `total` / `remaining` 全部被忽略；
- `ensureWindow()` 的第一道闸就是 `loadedThrough >= totalCount`，而 0 >= 0 恒真 → 永远不取数；
- `restart()` 也救不回来（`total = totalCount = 0`）。

用户看到的是「保留 0 / 移动 0 / 回收 0」的完成卡片，而照片库里一张都没少。

**触发路径**：进整理页 → 按 Home → 系统在后台杀掉进程（低内存机型上很常见）→ 从最近任务回来。

**修复方向**：在数据未就绪时**不要创建**这个 ViewModel（把 `libraryState.overview == null`
纳入 `LoadingScreen` 的判断，或把 review 分支挪到「概览已就绪」之后）；或者把 `total`/`remaining`
从构造参数改成随 `state` 推入的可变字段，让它在概览到达时能自我修正。前者更干净，也更符合
「View 只消费已就绪的数据」这条既有约定。

---

## Required（发版前应处理）

### R1. `ReviewedLedger` 的全量重写是 O(N²) 的主线程 JSON 序列化

`RealSessionStore.saveReviewed()` 每次把**全部** id 序列化成 JSON：

```kotlin
prefs.edit().putString(KEY_REVIEWED, JSONArray(imageIds).toString()).apply()
```

`ReviewedLedger` 的节流是「攒够 20 条或隔 1 秒」，也就是每滑 20 张就在**主线程**（滑动回调线程）上重建一次
越来越长的 JSON 字符串。总代价 ≈ N²/40 次字符串拼接：

| 已整理张数 | 每次 flush 的字符串 | 备注 |
|---|---|---|
| 2,000 | ~14 KB | 无感 |
| 20,000 | ~140 KB | 每次 flush 都在主线程分配 140KB，可感知 |
| 125,000（500GB 库） | ~900 KB | 每秒一次的主线程长字符串构建 |

注释里承认了「每秒往闪存里灌几百 KB」，但只解决了**频率**，没解决**每次都是全量**这件事。
`apply()` 的异步只覆盖磁盘写入，`JSONArray(...).toString()` 是同步的。

**修复**：改成增量追加 —— 按序号或时间批次分键存（`reviewed_batch_0`、`reviewed_batch_1`…），
每次只追加新批次；或者直接存 `LongArray` 的二进制（`BitSet` 更省：ids 是递增的行号，
用 `Bitmap`/`RoaringBitmap` 思想存位图）；库再大就上 Room 表。**不要**继续用「一个 key 存全量 JSON」。

### R2. 提交进行中，决定按钮仍然可点

`ReviewScreen` 里动作条的 gating 只有 `busy = state.preparing`：

```kotlin
ReviewActionBar(canUndo = state.canUndo, decisionsEnabled = canDecide, busy = state.preparing, ...)
```

`state.submitting` 没有参与。提交协程正在跑（可能正挂在系统授权框上）时，用户仍可以继续
keep / trash / move，于是队列里会出现「不在本次授权范围内」的新项。不会崩，也不会丢数据，
但「提交中」的语义和队列状态自相矛盾，完成页数字的口径也会漂。

**修复**：`busy = state.preparing || state.submitting`（`decideEnabled` / `undoEnabled` 已由 `busy` 统一门控）。

---

## Optional / Nit

- **`ReviewViewModel` 已 819 行、扛了 4 件事**（分页窗口、会话决定与撤销、持久化、提交编排）。
  C1 的根因就是其中「分页窗口」这一块的索引数学没人能一眼看懂。建议先抽 `ReviewQueueWindow`（见 C1 方案 2），
  再考虑把提交编排挪进一个 `CommitCoordinator`。**先拆再改，别在 800 行的文件里继续加分支。**
- `MediaStoreRepository.openCursor(... onlyTrashed = null)` 的注释写「null = 两者都要」，但平台默认是**排除**
  回收站项。当前 `imagesByIds` 的四个调用方都不涉及已回收的照片，所以行为正确，**但注释在撒谎** —— 下一个
  改这里的人会踩坑。改成三态枚举或写清「null = 交给平台默认（排除）」。
- `duplicates` 页面每次进入都会重扫（`LaunchedEffect(Unit) { onScan() }`），即使 `duplicateScanned` 已经是
  true 且结果还在内存里。用户来回切一次页面就要多解码最多 800 张缩略图，而且会先闪一下扫描态。
  建议 `if (!scanned) onScan()`。
- `LibraryViewModel.onAccessResolved` 的 `restoreSessionOnce` 只在 refresh 成功后调用；若首次扫描失败，
  暂存队列的恢复会被推迟到下一次成功扫描。行为可接受，但值得在注释里点明依赖关系。
- `PhotoTidyRoot` 里 `setShowEmptyAlbums` 被调用两次（`LaunchedEffect(settings.showEmptyAlbums)` 与
  `onUpdate` 里各一次）。幂等，但是两套同步机制并存，留一条就够。
- `SettingsScreen` 的 `showEmptyAlbums` 文案说「相册页是否显示 0 张的文件夹」，但 `MediaAlbum` 全部来自
  「有照片的桶」聚合，`count` 恒 > 0 —— 这个开关目前**没有任何可见效果**。要么实现它（列出设备上的空目录），
  要么删掉，别留一个骗人的开关。
- `allowBackup="true"` 会把 `phototidy_session`（id 列表）备份走。换机恢复后这些 id 在新设备上全是垃圾，
  `staging` 会被 `imagesByIds` 自然清掉，但 `reviewed_ids` 会作为无效数据长期留存。可以把它排除出备份规则。
- `ReviewedLedger.flush()` 里 `dirty` / `lastWriteAt` 没有同步保护。当前所有 `mark` / `unmark` 都在主线程，
  但 `contains` 在 IO 线程 —— 一旦将来有人从别的线程调 `mark`，这里会静默丢写。加 `@Volatile` 或注释锁死
  「只在主线程写」。
- `MediaOpCoordinator.waiting` 跨线程读写（`awaitConsent` 在调用方调度器，`resumeConsent` 在主线程），
  靠 `continuation.isActive` 兜底。目前 `gate` 把窗口压得很小，属于「能用但脆」。要收紧就给它加锁。
- `deferCurrent` / `deferred` 目前**零测试覆盖**。C1 修完之后请务必补一条回归测试（"deferred 照片滑到最后仍然可操作"）。
- `discardChanges()` 里 `keptCount--` 之后紧接着 `keptCount = 0`，前者是死代码。

---

## 已核查、确认**没有**问题的部分（供后续改动参考）

- 写操作三种语义的区分（`createWriteRequest` 仅授权 + 应用自己 update / `createTrashRequest` 与
  `createDeleteRequest` 授权即执行 + 应用什么都不做）实现正确，`attempt > 0` 短路没有漏洞。
- `MediaOpCoordinator` 的授权预算上限、`gate` 串行化、`withTimeoutOrNull` 兜底、以及
  「按订阅数而非 `tryEmit` 返回值判断无订阅者」，四条防线都在，且都有单测钉住。这套是本次审核里最扎实的一块。
- `MediaStoreRepository.filter()` 里月份区间的 `CAST(? AS INTEGER)` 是必须的（无亲和性的
  `COALESCE` 表达式 + TEXT 绑定参数 = 永远匹配 0 行），注释解释准确，`MonthWindow` 有 DST / 跨年 / epoch 0 的单测。
- `VISIBLE` 过滤（`IS_PENDING = 0 AND SIZE > 0`）、`isScreenshotPath` 用路径前缀而非桶显示名、
  `isImageDestinationLegal` 的大小写不敏感比较、`computeTargetPath` 复用真实路径 —— 全部正确，
  且都避开了「桶名是本地化显示名」这个坑。
- `DuplicateFinder` 的两级判据、`groupByFingerprint` 只认完全相同指纹、`ALLOCATOR_SOFTWARE` +
  显式 `recycle()`、`MAX_HASHES` 预算上限 —— 正确，且有 4 条单测。
- `pointerInput` 里阈值现算（不冻结成 1px）、`graphicsLayer` 里读高频量（不逐帧重组）、
  `derivedStateOf` 收窄失效范围、`neverEqualPolicy` 抑制滚动期重组 —— 这几处性能手法都是对的。
- `ReviewedLedger` 的「已整理」语义（保留也记账、撤销时 unmark、还原回收站时 unmark、
  跨范围全局共享）与 `LibraryOverview.remainingCount` 的派生口径自洽。
- `LazyColumn`/`LazyRow`/`LazyVerticalGrid` 的 key 唯一性：逐一核对过（含合成 bucketId 哨兵区间
  与 `bucketId:name` 组合 key 的防撞车设计），未发现重复 key 崩溃风险。
- 单测覆盖质量高：`ReviewViewModelTest` 22 条覆盖了撤销 / 分页补窗 / 账本 / 提交失败对账 / 丢弃改动只回滚本次
  等真实缺陷场景；纯函数（路径规则 / 分组 / 月份窗口 / scope 编解码）全部被钉住。

---

## 建议的处理顺序

1. **C1**（用户点了就中招，且有现成最小复现）
2. **C3**（用户会看到「应用在说谎」，属于最伤信任的一类）
3. **C2**（一个判断条件，改动最小）
4. **R1 / R2**
5. 借 C1 的修复把 `ReviewQueueWindow` 抽出来，把索引数学钉进单测
6. 清掉 Optional 里的「开关没有效果」「注释在撒谎」这类小账

---

## 修复记录（2026-10-12 第二轮，同日完成）

| 编号 | 状态 | 落点与手法 |
|---|---|---|
| **C1** | ✅ 已修 | 新增 `ui/review/ReviewQueueWindow.kt`：队列三段（已加载 / 未加载 / 稍后再看）的位置与取出规则收进同一个类，`take()` 与 `peek()` 挨着实现、口径由 `ReviewQueueWindowTest` 钉死。`ReviewViewModel` 只留下编排职责。 |
| **C2** | ✅ 已修 | `onAccessResolved` 在 `isPartial` 时每次回前台都重扫（Partial 是不稳定状态，不能只比枚举值）；`refresh()` 加并发合并 —— 重复请求不丢也不叠加，跑完补一次。 |
| **C3** | ✅ 已修 | `PhotoTidyRoot` 改为「概览未就绪就不建 `ReviewViewModel`」；另加一条兜底：首扫失败导致概览永远不来时自动退回首页，不留永久转圈。 |
| **R1** | ✅ 已修 | `SessionStore` 的账本改成**增量批次分键**（`appendReviewed(marked, unmarked)` + 阈值合并），`ReviewedLedger` 记录自上次落盘的增量。写入代价从 O(账本总量) 降到 O(批量)。编解码抽成不依赖 `org.json` 的纯函数，可在 JVM 单测里直测。 |
| **R2** | ✅ 已修 | `submitting` 参与动作条 / 快捷移动条 / 卡片手势三处门控；顺带把 `flyOutTo` 的 `runCatching` 换成 `try/finally` —— 前者会吞掉 `CancellationException`，让「已取消的移动」照样派发出去。 |
| Nit 组 | ✅ 已清 | `onlyTrashed = null` 的注释改写（不再谎称「两者都要」）；重复照片页不再每次进入都重扫；`discardChanges` 的死代码；`ReviewedLedger` 的线程约定写明；`MediaOpCoordinator.waiting` 加 `@Volatile`；删掉那个永远不生效的「显示空文件夹」开关；`allowBackup` 早已全量排除 sharedpref（无需改动）；顺手清零了 6 条 `UseKtx` + 1 条 `InlinedApi` + 1 条 `UnusedResources` 的历史 lint 告警。 |

### 验证

```
./gradlew :app:testDebugUnitTest   → 90 条全绿（修前 67 条）
./gradlew :app:assembleDebug       → ✅ 60.3 MB
./gradlew :app:assembleRelease     → ✅ 3.0 MB（R8）
./gradlew :app:lintDebug           → ✅ 0 error，13 条全是 Hint / 依赖版本提示
```

新增测试 23 条：`ReviewQueueWindowTest` 9 条（含 C1 的回归闸门）、`ReviewedLedgerTest` 6 条、
`ReviewedBatchCodecTest` 7 条、`ReviewViewModelTest` 里「defer 之后滑到底仍然可操作」1 条。

⚠️ **本轮没有做真机走查** —— 审核与修复时 Redmi 4X 未连接（`adb devices` 为空）。
C1 的手势链路（菜单「稍后再看」→ 滑到底 → 那张照片重新可操作）建议在设备接上后按 README 的
「实机验证」章再走一遍。

### 还给下一步留着的（本轮刻意没做）

- 账本的**磁盘**写入仍是 SharedPreferences 的整文件重写（异步、不在主线程）。要连这一半也去掉，
  得把账本挪出 SharedPreferences（追加式二进制日志 / Room 表）—— 另一个量级的改造，收益只在极大库上体现。
- `SharedPreferences.edit()` 的 `commit=true` 版在首帧同步读的场景没有需求，未改动。
- `ReviewUiState.committed` 目前**只有测试在读**，UI 没有消费它。它编码的是「这一轮已经落盘过」这个事实，
  删掉会连带删掉 3 条有意义的断言 —— 要么给它一个真实出口（完成页显示「已提交」之类），
  要么在下次重构时连测试一起清掉。**不要**让它继续以「只有测试在用」的状态躺着。
- `TrashScreen` 的 `ModifierParameter` lint 告警（`modifier` 不是第一个可选参数）与
  `OldTargetApi`（targetSdk 36 vs 37）是历史遗留，与本次改动无关，未动。

