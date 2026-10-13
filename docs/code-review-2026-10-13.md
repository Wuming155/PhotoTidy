# PhotoTidy 全量代码审核（第三轮）+ 对抗式复核终审记录

> 续 `docs/code-review-2026-10-12.md`（第一轮审核 → 第二轮修复，C1–C3 / R1–R2 / Nit 组已完成）。
> 本文件登记**第三轮全量审核的全部发现**，以及随后**七轮对抗式复核**的定性变更、认错记录、最终实施规范与批次计划。
>
> ⚠️ **本轮只做审核与裁定，未修改任何代码。** 下文所有「实施规范」均为待落地状态。
> 行号以审核时工作区（2026-10-13）为准。

---

## 0. 元信息

| 项 | 内容 |
|---|---|
| 审核范围 | `app/src` 全部 50 个 Kotlin 文件（约 9,500 行）+ `AndroidManifest.xml` + `res/` 资源 |
| 审核轴 | 正确性、数据一致性、状态机生命周期、并发/竞态、架构、可维护性 |
| 方法 | 逐文件通读 + 关键路径静态推演 + 与既有单测/审核文档交叉验证 |
| **未做** | **未执行 `./gradlew`，未连接真机（与第一轮同样受设备条件限制）**；全部结论为静态推演，凡涉及平台行为的推断均已标注置信边界 |
| 编号 | P1–P9：第三轮新发现；**P10–P12：对抗复核过程中新增** |
| 状态 | 见 §5「批次落地矩阵」；**代码尚未改动** |

### 0.1 分级口径

| 级别 | 含义 |
|---|---|
| **必修** | 用户可见的错误承诺、数据/账本不一致、或会让会话进入自相矛盾状态的缺陷 |
| **次要加固** | 窄窗竞态、判据分叉、可被上游误伤的状态管理 |
| **文档修正** | 代码行为正确但与注释/能力声明不符 |
| **Nit** | 语义冗余、UI 一致性 |
| **已撤回** | 经对抗复核被推翻，或判定为「产品语义选择」而非缺陷 |

---

## 1. 总体结论

写操作安全模型、授权协调器（`MediaOpCoordinator`）、以及第三轮新引入的分页窗口抽象（`ReviewQueueWindow`）三块经得起检查，未发现新的数据破坏路径。

本轮缺陷集中在三处**「一个状态被两个职责共用」**的结构性问题上：

1. **`history`（撤销栈）同时充当「本次会话的改动索引」** → P2（提交后未封账）、P11（`restart()` / 跨范围 / 冷启动时索引与实体脱节）、P12（「全部还原」越过会话边界）；
2. **`StagingTrash` 是进程级全局队列，但从无任何地方建模它的归属** → P4b（并发覆写快照）、P11/P12 的口径错位；
3. **「提交中」这条不变式只在三处 UI 上落实**（R2 的修复范围），数据层没有任何自防线 → P10。

另外两项是确定性的用户可见缺陷：P1（灵敏度映射颠倒）、P5（退化指纹误判，升级为必修）。

**统计**：必修 6（P1 / P2 / P4a / P5 / P10 / P11）、次要加固 3（P3 / P4b / P12）、文档 1（P5′）、Nit 2（P6 / P7）、已撤回 2（P8 / P9）。

---

## 2. 缺陷清单

### P1 — 滑动灵敏度映射颠倒（用户可见缺陷）

| 项 | 内容 |
|---|---|
| 级别 | **必修** |
| 证据 | `ui/review/SwipeablePhotoCard.kt:115,117`、`core/settings/AppSettings.kt:18-19`、`ui/settings/SettingsScreen.kt:234-241`、`res/values/strings.xml:167-171` |

**机理**：阈值用除法计算：

```115:117:app/src/main/java/com/phototidy/ui/review/SwipeablePhotoCard.kt
    fun thresholdX(): Float = (cardSize.width * 0.28f / sensitivity).coerceAtLeast(1f)

    fun thresholdY(): Float = (cardSize.height * 0.22f / sensitivity).coerceAtLeast(1f)
```

| 滑杆值 | UI 文案（现状） | 实际阈值 | 实际手感 |
|---|---|---|---|
| 0.6 | 灵敏 | 宽 × 46.7% | **最难触发** |
| 1.0 | 标准 | 宽 × 28% | 标准 |
| 1.8 | 稳重 | 宽 × 15.6% | **最灵敏** |

用户拖到「灵敏」一侧反而更难触发，整个区间被镜像。

**定性变更**：我方最初主张「改代码，不改文案」，被对抗方驳回。理由成立：字段名 `swipeSensitivity` 与操作系统通用语义（值越大越灵敏）都支持除法公式；真正自相矛盾的是**四方不一致**——字段名、字段 KDoc（「阈值倍率：越小越容易触发」）、`strings.xml` 文案、`SettingsScreen` 档位映射。选定「保留公式、改文案与档位」为最终方向。

**实施规范**：
1. `SwipeablePhotoCard` 的除法公式**不动**；
2. 交换 `settings_sensitivity_low` / `settings_sensitivity_high` 的文案映射（低值端 → 「稳重」，高值端 → 「灵敏」）；同步 `settings_sensitivity_desc`；
3. 把 `AppSettings.swipeSensitivity` 的 KDoc 改为「灵敏度，越大越灵敏」，并修掉「阈值倍率」这个错误自称。

---

### P2 — 提交成功后未封账（状态机生命周期缺陷）

| 项 | 内容 |
|---|---|
| 级别 | **必修** |
| 证据 | `ui/review/ReviewViewModel.kt:454-519`（`commit()` 无 `history` 清理）、`:632`（`canUndo`）、`:277-301`（`undo()`）、`:392-410`（`discardChanges()`）、`:637-640`（`committed` 派生） |

**机理**：`commit()` 成功时只做 `pendingMoves.remove(...)` / `staging.remove(...)`，**`history` 原样保留**，于是 ↺ 撤销与「丢弃改动」都能逆流到**已经落盘**的决策上，并对它们调用 `reviewed.unmark(...)`。

**已认错的分支（对抗复核第一轮）**：我方原报告称「账本被抹 → 冷启动重回队列 → 二次清除 → staging 卡死」这条链**对 Trash 不成立**。`pageImages` 走 `MATCH_EXCLUDE`（`core/media/MediaStoreRepository.kt:284-295,537-542`），已回收项在 ContentProvider 阶段即被排除，**不可能重回队列**。该分支撤回。

**仍然成立的分支**：**Move**。被移动的照片仍在库中，`MATCH_EXCLUDE` 拦不住它：

1. 移动 B → `pendingMoves[B]` + `history += Move(B)` + `reviewed.mark(B)`；
2. 提交成功 → 只 `pendingMoves.remove(B)`，`history` 仍在；
3. 连续 ↺ 撤到那条 `Move(B)` → `reviewed.unmark(B)` + `queue.pushFront(B)`；
4. **该照片（此时已在 `DCIM/B/`）在下一次冷启动被 `loadOverview` 正常计入「待整理」并重新排队**，且 `pushFront` 携带的是过期的 `relativePath`。
   同一根因还有第二个出口：`discardChanges()` 会回滚整份 `history`，把已落盘项的账本一并抹掉。

**定性变更**：我方最初建议「`undoEnabled = canUndo && !committed`」被驳回且成立——`committed` 是单向门闩，而「中途提交后继续整理」是受支持的正常流程，该门控会永久剥夺后续新滑出照片的撤销权。该建议撤回。

**实施规范（(γ) 出栈封印）**：

- `commit()` 的 `submitting = true` **提到 `viewModelScope.launch` 之前**同步置位（现写法之所以能挡双击，仅依赖 `Dispatchers.Main.immediate` 的内联派发，属隐式依赖）；
- `finally` 内从**状态派生**（不用回调副作用收集）：
  ```kotlin
  val settledMoveIds  = moves.map { it.imageId }.filterNot { it in pendingMoves.keys }.toSet()
  val settledTrashIds = trashes.map { it.id }
      .filterNot { id -> staging.items.value.any { it.id == id } }.toSet()
  val hasAnySettled   = settledMoveIds.isNotEmpty() || settledTrashIds.isNotEmpty()
  ```
  该派生式天然覆盖「全成功 / 部分成功 / 全失败」以及 `op()` 抛异常（此时 `dropUnreachableMoves` 不执行，差集为空 ⇒ 不封印）三种结果；
- 摘除不可逆项：`history.removeAll { r -> (r.action == Move && r.image.id in settledMoveIds) || (r.action == Trash && r.image.id in settledTrashIds) }`；
- **成功门控封印**：`if (hasAnySettled) history.removeAll { it.action == Keep }`
  （「一个零持久化效果的提交不是检查点」。**全失败/全拒绝时严禁封印任何 Keep**——否则 `discardChanges()` 无法回滚本次会话的 Keep）；
- **封印 = 出栈，不引入 `ReviewRecord.isCommitted` 字段**。理由：被永久封印的记录留在撤销栈里，只会不断制造「两个出口口径不一致」的不一致（见下）；`history` 的唯一职责就是撤销栈，出栈后 `canUndo = history.isNotEmpty()`、`undo() = removeLastOrNull()`、`discardChanges()` 三者全部回到更简单的现成形态；
- 出栈时**计数器不动**（那些照片仍是「已决定」），维护不变量：**`processedCount ≥ history.size`**；
- 本批次**保留** `session.saveStaging(...)`，待批次二 P4b 一起剥离。

**被否决的备选**（记录备忘）：
- `sealedBefore = history.size` 下标封印 —— 存在**游标脱节**：`history` 中已结算项与未结算项交错，先算/后算两种顺序都会误封印未提交项；
- `ReviewRecord.isCommitted` 记录级标识 —— 同时给出 `canUndo = history.any { !it.isCommitted }`（跳过语义）与 `undo() { if (last.isCommitted) return }`（屏障语义）会互相矛盾，在「移动成功 + 回收站被拒 + 按撤销」这条常规路径上产出**撤销键亮着、点了没有任何反应**——即 `ReviewQueueWindow` 文档明确警告的那类「两个出口对不上」的缺陷；且 `ReviewUiState` 是 data class，`canUndo` 只能留在 `syncState()` 里赋值，无法按变量方式派生。

---

### P3 — 截图判据两套实现分叉

| 项 | 内容 |
|---|---|
| 级别 | **次要加固**（原定为「主要」，经复核降级） |
| 证据 | `core/media/MediaStoreRepository.kt:684-691`（生产 SQL）、`:766-773`（`isScreenshotPath`）、`:496-498`（`MediaScope.Screenshots`）、`test/.../AlbumRulesTest.kt:56-70` |

**机理**：生产判据是 `RELATIVE_PATH LIKE 'Pictures/Screenshots%'`（无边界约束），会命中 `Pictures/ScreenshotsBackup/` 等目录；而 Kotlin 侧 `isScreenshotPath` 要求前缀后紧跟 `/` 或到头。**`isScreenshotPath` 全仓只有测试引用**（`AlbumRulesTest.kt:58-69` 六处断言），于是那条「前缀必须止于路径边界」的断言保护的是一个**不参与生产**的函数——测试给出的是虚假安全感。

**定性说明**：对抗方指出「Kotlin 纯函数无法下推到 MediaProvider 进程」这一点从未被我方主张（我方主张的是**收紧 SQL 参数**，不需要跨进程改造）；「ScreenshotsBackup 目录极罕见」成立，故级别降为次要加固。

**实施规范**：
1. 生产子句改为**双 `LIKE ?`**（务必不要用 `=` / `IN`——它们走 BINARY 排序规则，会丢掉现注释所依赖的 ASCII 大小写不敏感，见 `MediaStoreRepository.kt:496`）：

   ```kotlin
   const val SCREENSHOT_PATH_CLAUSE =
       "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
           "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
   val SCREENSHOT_PATH_ARGS = arrayOf(
       "Pictures/Screenshots",      // 兼容无尾斜杠的写入（大小写不敏感）
       "Pictures/Screenshots/%",    // 常见形态：目录内文件 / 子目录
   )
   ```
   `DCIM` 一条同理。`LIKE '.../%'` 既覆盖目录内文件，也能排除 `ScreenshotsBackup/`；
2. 让 `isScreenshotPath` 与生产判据同源（或删除该纯函数、改测 SQL 子句），消除「测试保护死代码」。

---

### P4a — `restoreSessionOnce` 的 `runCatching` 会把「取消/失败」变成「永久清空快照」

| 项 | 内容 |
|---|---|
| 级别 | **必修** |
| 证据 | `ui/LibraryViewModel.kt:301-319`（关键行 `:307-309`） |

```306:310:app/src/main/java/com/phototidy/ui/LibraryViewModel.kt
        viewModelScope.launch {
            val images = runCatching { repo.imagesByIds(ids) }.getOrDefault(emptyList())
            if (images.isEmpty()) {
                session.clearStaging()
            }
```

**机理**：`runCatching` 吞掉 `CancellationException`。Activity finish / ViewModel clear 时恰有一笔在飞的恢复查询 ⇒ 取消被吞成 `emptyList()` ⇒ **`session.clearStaging()` 把整份「待清除」快照永久删掉**；一次 MediaProvider 重启或权限范围变化造成的瞬时异常同理。

这与仓库自身的约定冲突：同一文件 `refresh()` 的四路查询专门用了 `runCatchingUnlessCancelled`（`util/Coroutines.kt:11-18`），注释写明「取消是流程控制不是失败」。

**该条在对抗复核中被对方明确采纳、我方坚持保留**（对方第一轮抗辩只覆盖了 P4b 的竞态，未触碰这一条）。

**实施规范**：改用 `runCatchingUnlessCancelled`；**只有 `Result.success(emptyList)` 时**才 `clearStaging()`，失败/取消一律保留快照等下次重试。

---

### P4b — `persist()` 越权重写全局暂存快照（窄窗竞态）

| 项 | 内容 |
|---|---|
| 级别 | **次要加固** |
| 证据 | `ui/review/ReviewViewModel.kt:653-656`（`persist()`）、`:669-714`（`restorePendingMoves` 结尾调 `persist()`）、`ui/LibraryViewModel.kt:301-319`（异步 staging 恢复） |

**机理**：`ReviewViewModel.persist()` 把**进程内** `staging.items` 无条件写回全局快照。若「无待移动项」短路未命中（`:671`）而 staging 的异步恢复尚未完成，`persist()` 会把磁盘上的暂存快照覆写成空。触发需要同时存在「本范围的待移动快照 + 全局暂存快照」，且存在两个入口：
- 用户快速进入整理页；
- **进程被杀后直接恢复到整理页**——此时 `ReviewViewModel` 由 `overview` 就绪那一帧**自动**构造（`ui/PhotoTidyRoot.kt:270-272` 的 `LoadingScreen` 分支只推迟到概览到达），不需要任何用户操作；两个恢复查询并发，`imagesByIds` 对暂存列表会 `chunked(900)` 切多片，后完成完全可能。

**概率说明（收敛后）**：对抗方指出「`LoadingScreen` + `snapshot.isEmpty()` 短路砍掉了绝大多数进入条件」——成立，我方接受降级；但**进程被杀恢复路径移除了「用户必须快速点击」这一缓解条件**，因此它是「低概率但非零」，不是理论真空。实际损害还需「第二次进程死亡前没有任何 staging 变更」，故定级为次要加固。

**根因（采纳对抗方定性）**：`persist()` 把暂存与待移动两个职责捆绑在一起 —— `restorePendingMoves()` 只恢复移动项，却去重写全局 `Staging`。

**实施规范**：
1. `StagingTrash(sessionStore)` **自持落盘**，并暴露 `restored` 就绪门控（恢复前拒绝任何落盘）；
2. `ReviewViewModel` 彻底剥离 `saveStaging`（`persist()` 只剩 `savePendingMoves`）；
3. 三条落地约束：
   - `StagingTrash` 目前**零依赖**（无构造参数），需注入 `SessionStore` 或回调；
   - `StagingTrashTest` 三条用例直接 `StagingTrash()` 构造，需同步改为注入假实现；
   - 装配点唯一：`Graph.stagingTrash by lazy { StagingTrash(sessionStore) }`；
4. **必须与 P4a 同批次**；`commit().finally` 中 `saveStaging` 的移除也在此刻一并执行（见 §5 批次依赖）。

---

### P5 — 重复照片检测：dHash 退化指纹会把不同照片判为同一组

| 项 | 内容 |
|---|---|
| 级别 | **必修**（对抗复核后升级，原为「次要」） |
| 证据 | `core/media/DuplicateFinder.kt:99-133`（关键 `:119-127`）、`:158-159`（`groupByFingerprint`）、`ui/duplicates/DuplicatesScreen.kt:90-101`（默认勾选除第一张外全部） |

**机理**：指纹判据是严格 `left > right`，于是**纯色图**（下采样后每行像素相同）在所有比较上为假 ⇒ `hash == 0L`；**横向单调**的图同样得到全 0（递减）或全 1（递增）。`groupByFingerprint` 只按指纹完全相等分组，所以两张内容不同的退化图，只要**文件字节数相同**就被报成一组重复，进入「批量移入回收站」的确认框（该页默认勾选除第一张外全部）。

**升级理由（对抗双方一致）**：dHash 的判据是严格 64 位相等、零汉明容差，而 9×8 下采样本身就抹平了连拍之间的传感器噪声差异——**连拍误报的真实防线是 Stage 1 的字节精等，而不是指纹的颗粒度**。这意味着在 `0L/-1L` 这一类上指纹提供的是**零判别力**，整套防御只剩 Stage 1 在撑。

**被否决的两条修复路线（撤回）**：
- 「直接丢弃 `0L/-1L` 指纹」——会造成**真实纯黑/纯白重复废片永久漏检**（漏报比误报更隐蔽）；
- 「放宽第一级（按尺寸容差 / 像素尺寸分组）」——在「默认勾选除第一张外全部 + 出口是批量回收站」的交互下会把连拍规模化误删，**必须坚决驳回**。

**实施规范**：
1. **守死 Stage 1 单字节精等，绝对不放宽**；
2. 对 `0L/-1L` 候选追加**均值亮度 / 采样像素二次校验**（校验逻辑放在 `findDuplicates` 一侧，不要污染泛型函数 `groupByFingerprint<T>`）；
3. 补单测：同一组退化指纹 + 亮度差异明显时不得合并。

---

### P5′ — `DuplicateFinder` 的 KDoc 夸大了容错能力（文档缺陷）

| 项 | 内容 |
|---|---|
| 级别 | **文档修正** |
| 证据 | `core/media/DuplicateFinder.kt:31-35` 与 `core/media/MediaStoreRepository.kt:249-276`（`findSizeCandidates` 按 `SIZE` 精确碰撞） |

**机理**：KDoc 称 dHash「天然容忍重新编码、元数据差异、轻微缩放，而这些正是『同一张照片存了两遍』最常见的形态」，但 Stage 1 只把**字节数完全相同**的候选送进来，于是「重新编码副本」在进入 dHash 之前已被筛掉——**第二级的实际职能退化成「同字节数候选的误报过滤器」，其宣称的容错能力在架构上不可达**。

**实施规范**：修正 KDoc，如实声明只匹配字节完全一致的候选；若将来要兑现「容忍重新编码」，须另行设计第一级（**不在本轮范围**，且需先解决 P5 的误删风险）。

---

### P6 — 整理「当前所在的相册」时，该相册会把自己列为移动目标

| 项 | 内容 |
|---|---|
| 级别 | **Nit** |
| 证据 | `ui/LibraryViewModel.kt:427-453`（关键 `:429-443`） |

**机理**：排除当前相册只作用于 `fromDevice`，而紧接着的「系统标准目录补齐」是**按路径**在 `fromDevice` 上判重。进入 `DCIM/Camera` 时它被排除 ⇒ `standardDirsMissing` 认为该路径不存在 ⇒ 又把它合成回来。结果排出一个目标路径等于当前路径的 `PendingMove`，提交时把 `RELATIVE_PATH` 写成原值并计为「移动成功」。

**定性**：对抗方指出该写操作幂等无害、无数据损坏风险 —— 成立，所以我方原「纯噪音写操作」的定性保留但不升级；答辩中「列为主要审查成果」的说法与原报告不符（P4–P9 均归在次要章节）。

**实施规范**：`standardDirsMissing(existing, standard)` 传入**未排除的完整列表**，即可同时满足「当前相册不出现在目标里」与「标准目录不被同名真实目录挤掉」。

---

### P7 — 「重复照片」页仍会显示首页悬浮按钮

| 项 | 内容 |
|---|---|
| 级别 | **Nit** |
| 证据 | `ui/PhotoTidyRoot.kt:464-473`（FAB slot，条件在 `:465`）、`:487-492`（`destinationKey` 声明位置） |

**机理**：FAB 条件 `currentTab == RootTab.Home && !showSettings && !showTrash && hasLibrary` 显式排除了次要页面的 `showSettings` / `showTrash`，**漏了 `showDuplicates`**；而 `currentTab` 此时仍是 `Home`，于是重复照片页上浮出一个与本源无关的「开始整理」按钮，压在该页列表右下角（该页底部自身还有一个主操作按钮）。

**实施规范（采纳对抗方更优方案）**：把 `destinationKey` **提升到 `Scaffold` 之外**作为导航单一真源，FAB 改用 `destinationKey == RootTab.Home.name` 门控。
注意：`destinationKey` 现声明在 `Scaffold` 的**内容 lambda 内部**（`:487-492`），而 `floatingActionButton` 是另一个 slot lambda（`:464`），**两者不共享局部作用域**——该方案不是一行替换，而是需要一次小的状态提升重构。

---

### P10 — 提交期间队列并未冻结（R2 漏掉的第四/五/六处门控）

| 项 | 内容 |
|---|---|
| 级别 | **必修（P2 的前置条件）** |
| 证据 | `ui/review/ReviewScreen.kt:296-311`（⠇ 菜单三项）、`:231`（范围切换按钮）、`ui/PhotoTidyRoot.kt:237-248`（`BackHandler`）、`:414-428`（退出确认框）、`:208-210`（`openScope`）、`ui/review/ReviewViewModel.kt:352-378`（还原）、`:392-410`、`:454-519` |

**机理**：R2 的修复确立了「提交期间不改队列」这条不变量，并落实在三处：

```410:412:app/src/main/java/com/phototidy/ui/review/ReviewScreen.kt
            // busy 同时带上 submitting：提交期间 keep / trash / undo 都必须停手 ——
            // 改动是在内存里排队的，而提交只对**发起那一刻**的队列负责，
```

三处是动作条、快捷移动条、卡片手势。**但还有三处没关**：

1. **⠇ 菜单**：`menu_staging` / `menu_defer` / `menu_restart` 均无 `submitting` 门控（后两项连 `enabled` 都没有），入口按钮本身也没有；
2. **退出确认框**：`BackHandler` / 顶栏 X 在提交期间照样弹框，框内「丢弃改动」无门控；
3. **顶栏范围切换按钮**：无门控。

**后果链（「整理中回收站」的还原被在飞提交吞掉）**：提交快照已在 `:458` 捕获 ⇒ 用户还原某张（`staging.unstage` + `reviewed.unmark` + `queue.pushFront`）⇒ 回收站阶段仍按快照把**包括那张在内**的整批移入系统回收站 ⇒ `onSuccess` 对已出队的 id 是空操作 ⇒ `doneLabel` 把用户刚撤销的那张也算进「已回收 M 张」。净结果：**反悔无效，且应用同时告诉用户「已还原」和「已提交成功」**。

窗口不是毫秒级：移动阶段是**逐条第 Binder 调用**（`core/media/MediaStoreRepository.kt:655-669` 的 `entries.forEach { resolver.update(...) }`），批量移动 200–500 张时，在系统授权框弹出之前有**数百毫秒到数秒**的完全可交互窗口。

「丢弃改动」变体更严重：用户选择丢弃 ⇒ 快照项被全部回滚 ⇒ **在飞的提交仍把它们写进系统相册**，随后 `onCommitted()` 刷新首页。

**为什么它是 P2 的前置**：P2 的封印边界（「提交结束时尚在 `history` 里的 Keep 全是检查点之前的」）建立在「提交期间不产生新记录」之上；而 `restoreStagedInternal` 会**删**记录、`restart()` 会**清空** `history`，边界不成立时封印规则出现时序歧义。

**实施规范**：
1. **VM 自防线**：`keep / trash / moveTo / undo / discardChanges / restoreFromStaging / restoreAllStaged / restart / deferCurrent` 入口统一 `if (_state.value.submitting) return`（所有读写都在主线程，**不需要加锁或 `@Volatile`**；注释须写明这是**兜底网**、可见入口均已门控——本仓库一贯反对静默 no-op）；
2. **UI 门控**：顶栏 ⠇ 按钮、**范围切换按钮**、关闭 X 统一 `enabled = !state.submitting`；
3. **返回拦截（关键，勿写成 `enabled` 条件）**：`BackHandler` 必须**保持** `enabled = reviewScope != null`，在回调内部静默消费：
   ```kotlin
   BackHandler(enabled = reviewScope != null) {
       if (reviewVmForBack?.state?.value?.submitting == true) return@BackHandler
       ...
   }
   ```
   若把 `submitting` 写进 `enabled`，返回事件会穿透到 Activity ⇒ `finish()` ⇒ `onDestroy` ⇒ `ViewModelStore` 清空 ⇒ **`viewModelScope` 取消、在飞的 `commit()` 被打断在半途**（`updateAll` 是逐条循环）。静默吞掉不需要额外提示——状态行此刻正显示 `state.submittingLabel`；
4. **导航层**：该入口不是 VM 方法，`openScope`（`PhotoTidyRoot.kt:208-210`）内读 `reviewVmForBack?.state?.value?.submitting` 拦截。

---

### P11 — 退出「丢弃改动」的承诺与能力不同源（第三轮新增）

| 项 | 内容 |
|---|---|
| 级别 | **必修** |
| 证据 | `ui/review/ReviewViewModel.kt:392-410`（`discardChanges` 靠重放 `history`）、`:418-429`（`restart()` 清 `history` 但保留排队项）、`:632`（`canUndo`）、`ui/LibraryViewModel.kt:301-319`（跨进程恢复暂存）、`StagingTrash` 为进程级单例 |

**机理**：`discardChanges()` 以「重放 `history`」为唯一回滚手段，而 `history` 同时承担「撤销栈」与「本次会话的改动索引」两个职责。任一「索引被清空但实体仍在」的路径都会让「丢弃改动(N)」静默丢弃 0 项。**共三条路径**：

1. **`restart()`**：`history.clear()`（`:422`）但 `staging` / `pendingMoves` 一个都没动（这正是其文档承诺「已排队待提交的改动保留」）。→ 3 步复现：滑 3 张清除 → ⠇「再来一轮」→ 按 X → 对话框「丢弃改动(3)」→ 点它 → 那 3 张原样留存。
2. **跨范围**：`stagedCount` 取自**全局** `StagingTrash`（进程级单例），而 `discardChanges()` 的作用域是**本次会话/本范围**。→ 复现：范围 A 攒 3 项并「保留退出」→ 进入范围 B → B 里什么都不做 → 按 X → 对话框仍写「丢弃改动(3)」→ 点它 → 丢弃 0 项。
3. **跨进程（冷启动）**：`restoreSessionOnce` 会把快照恢复进全局 `StagingTrash`，而**任何 VM 的会话索引都拿不到它们**（`restorePendingMoves` 只补 Move 记录）。

**为什么两种被否决的修法都不行**：
- (i) 「`restart()` 保留 pending 记录 + `processedCount = history.size`」→ 会破坏「新一轮第一张进度必须是 0」的语义（`ReviewScreen.kt:662` 读 `(processed + 1) / total`），与既有断言 `ReviewViewModelTest:159`（`assertEquals(0, processed)`）意图冲突；
- (ii) 「`history` 为空时置灰『丢弃改动』」→ **会造成死锁**：`pendingMoves`（移动项）在整个应用里**没有任何独立管理页**，唯一出口是 `undo()` 的 LIFO 回溯；`restart()` 之后置灰会把 3 张移动意图永久钉死。

**最终实施规范（(iv) = 索引 + 口径 + FIFO 语义修正）**：

1. **引入会话索引** `private val sessionStagedIds = HashSet<Long>()`，记录本会话送入暂存的 id。
   **钩子必须落在唯一收敛点 `restoreStagedInternal()` 内部**（不要挂在 `restoreFromStaging` / `restoreAllStaged` 两个公开入口上——「全部还原」走的是前者之外的路径，否则索引会漂移）；另需覆盖 `trash()` 增、`undo()` 遇 Trash 减、`commit()` 结算时移除 `settledTrashIds`；
2. **口径分离**（对话框与状态行回答不同的问题）：

   | 数字 | 口径 | 用途 |
   |---|---|---|
   | `pendingTotal`（现状） | 全局暂存 + 本范围待移动 | 顶栏状态行、提交徽标 |
   | `discardableCount`（新增） | `history.count { it.action == Keep } + sessionStagedIds.size + pendingMoves.size` | 退出对话框的「丢弃 N 项」与按钮可用性 |

   关键推论：`pendingMoves.size > 0 ⟹ discardableCount > 0`（每条待移动项必然由 `moveTo()` / `restorePendingMoves()` 追加过记录），**因此口径修正不会锁死任何东西**；(ii) 的死锁成因是「拿 `history` 当基座」，换成索引后自动消解。`discardableCount == 0 && pendingTotal > 0` 时对话框应显示「本次没有待丢弃的改动」，此时暂存项仍有出路（⠇ →「整理中回收站」）；
3. **`discardChanges()` 主体修正**（见 §2.1 的计数器不变量与 §3 的认错记录）：

   ```kotlin
   fun discardChanges() {
       if (_state.value.submitting) return

       // 1) 队列副作用：只丢本会话的暂存；本范围全部待移动
       sessionStagedIds.forEach { staging.unstage(it) }
       sessionStagedIds.clear()
       pendingMoves.keys.toList().forEach { pendingMoves.remove(it) }

       // 2) 账本与计数器：history 里只剩未提交项（已落盘/已封印的已在 commit 时出栈）
       while (history.isNotEmpty()) {
           val record = history.removeLast()
           reviewed.unmark(record.image.id)
           if (record.action == ReviewAction.Keep) keptCount--
           processedCount--
       }
       // 禁止在循环外归零：processedCount ≥ history.size 是不变量，
       // 封印项离开栈时计数器不动，留下的正是「已落盘/已封印」那部分

       persist()
       reviewed.flush()
       syncState()
   }
   ```
   **必须删除**原有的 `processedCount = 0` / `keptCount = 0`（见 §3 认错记录第 6、7 条）；
4. `restart()` 的现有实现与既有测试**保持不变**（(iii)/(iv) 让索引不再依赖 `history`，因此 `processed == 0` 与「保留排队项」同时成立）。

---

### P12 — 「全部还原」越过本会话边界（P11 同族，低危）

| 项 | 内容 |
|---|---|
| 级别 | **次要加固** |
| 证据 | `ui/review/ReviewViewModel.kt:360-366`（`restoreAllStaged` 遍历 `staging.items.value`） |

**机理**：`StagingTrash` 是全局队列，于是用户在范围 B 点「整理中回收站 → 全部还原」，会把**其他范围**排队的清除意图一并撤销并抹账。方向安全（只取消「打算删」，不动照片），但它是 P11 §3.2 同一个「全局队列 vs 本会话」混乱的镜像面。

**实施规范**：收敛到会话索引：`sessionStagedIds.toList().forEach { restoreStagedInternal(it) }`（借助 P11 的 `sessionStagedIds`，一行改动）。

---

## 3. 对抗复核过程中的定性变更与认错记录

七轮对抗复核共推翻/修正 14 处主张，逐条登记以备追溯。

| # | 主张方 | 原主张 | 最终裁定 |
|---|---|---|---|
| 1 | 审核方 | P2「冷启动重回队列 → 二次清除 → staging 卡死」 | **认错撤回**：Trash 侧被 `MATCH_EXCLUDE` 物理隔离，该链不成立；Move 侧成立（保留） |
| 2 | 审核方 | P2「`undoEnabled = canUndo && !committed`」 | **撤回**：`committed` 单向门闩会永久剥夺中途提交后新决定的撤销权 |
| 3 | 审核方 | P2「`sealedBefore = history.size` 下标封印」 | **撤回**：已结算与未结算项交错，两种计算顺序都会误封印未提交项（游标脱节） |
| 4 | 审核方 | P2 §2.3「现存所有 Keep 一律封印」 | **修正**：必须加 `hasAnySettled` 门控（零落盘的提交不是检查点） |
| 5 | 审核方 | P1「改代码不改文案」 | **改判**：保留除法公式，改文案与档位映射（字段名 + OS 通用语义都支持除法） |
| 6 | 审核方 | P5「直接丢弃 `0L/-1L` 指纹」 | **撤回**：会造成真实纯黑/纯白重复废片永久漏检 |
| 7 | 审核方 | P5′「放宽第一级」 | **撤回**：在默认勾选全部 + 批量回收站的交互下会造成规模化误删 |
| 8 | 审核方 | P2-Discard「保留原 `while` 循环、去掉计数器归零」 | **修正**：原循环体内没有任何递减，按字面实现会留下虚高计数；须补齐逐条递减 |
| 9 | 审核方 | P11 候选 (i)(ii) | **驳回**：(i) 破坏进度条语义；(ii) 的置灰半边会让 `pendingMoves` 死锁（该队列无独立管理 UI） |
| 10 | 审核方 | P4b 竞态概率表述 | **降级**：接受「窄窗」定性，但补充「进程被杀恢复路径无需用户操作」这一入口 |
| 11 | 对抗方 | P2 用 `ReviewRecord.isCommitted: var` + `canUndo` 派生在 `ReviewUiState` | **修正**：设计方向接受，但 `var` 进 data class 是坏味道（应 `val` + `copy`），且 `ReviewUiState` 是 data class、无法从 VM 私有 `history` 派生 |
| 12 | 对抗方 | P2 的 `canUndo = any{!isCommitted}` 与 `undo(){ if(last.isCommitted) return }` | **判定不自洽**：跳过语义与屏障语义混用，在「移动成功 + 回收站被拒 + 按撤销」路径上产出「撤销键亮着、点了无反应」；由 (γ) 出栈一并消除 |
| 13 | 对抗方 | P10「`ScopePicker` 未门控 → 双会话脑裂」 | **部分修正**：门控缺失成立；「脑裂」不成立（两个 VM 的 state 面隔离：快照按 scope 键、账本/暂存为刻意共享），实际后果是「用户不可观测的副作用」；且该入口不是 VM 方法，须在导航层拦截 |
| 14 | 对抗方 | P11 方案 (iii) 的 `discardChanges()` 主体 | **修正三处**：①尾部 `processedCount = 0` / `keptCount = 0` 是第四轮自己判定的「状态机断层」回归；②循环内 `keptCount--` 被紧随的归零覆盖（第一轮审核已点名并清除的死代码，见 `docs/code-review-2026-10-12.md:182` 与修复记录 Nit 组）；③索引钩子只挂在 `restoreFromStaging`，会让 `restoreAllStaged` 路径上的 `sessionStagedIds` 漂移 |

---

## 4. 已核查、确认无问题的部分

- **写操作三态语义**：`createWriteRequest`（授权后应用自己 `update`）与 `createTrashRequest` / `createDeleteRequest`（授权即执行、应用不二次 `update`）区分正确；`attempt > 0` 短路无漏洞；`updateAll` 检查 `rows <= 0` 而非只看异常（`MediaStoreRepository.kt:647-669`）。
- **授权协调器**：`consentBudget` 上限、`gate` 串行化、`withTimeoutOrNull` 兜底、按 `subscriptionCount` 而非 `tryEmit` 返回值判定「无订阅者」四条防线齐全；`awaitConsent` 中 `cancel()` 先于 `waiting = continuation` 的赋值顺序正确。
- **`ReviewQueueWindow` 不变量**：`peek` 的 `tailStart = ready.size + unloaded` 与 `take` 的「ready → unloaded>0 则 null → deferred」严格互补；`pushFront` 不与 `deferred` 重复计数。
- **月份区间的 `CAST(? AS INTEGER)`** 必须保留（无亲和性 `COALESCE` 表达式 + TEXT 绑定 = 恒假），注释解释准确。
- **`VISIBLE` 过滤、`isImageDestinationLegal` 大小写不敏感、`computeTargetPath` 复用真实路径、`Screenshot` 判据用路径而非桶显示名** —— 思路正确（唯 `isScreenshotPath` 未接进生产路径，见 P3）。
- **`ReviewedLedger`**：增量批次编解码的符号约定与 `Long.MIN_VALUE` / `0` 边界处理正确；`ids` 用 `ConcurrentHashMap.newKeySet()` 支撑「`contains` 在 IO 线程 / `mark` 在主线程」的约定；`flush` 的摘取前清空顺序正确。
- **Compose 侧性能手法**：`pointerInput` 阈值现算、`graphicsLayer` 内读高频量、`derivedStateOf` 收窄失效、`neverEqualPolicy` 抑制滚动重组、`derivedStateOf` 包裹选中计数 —— 全部正确。
- **`remember` 捕获局部委托变量**（`exitConfirmOpen` / `reviewState`）在 Kotlin 语义下读到的是**最新值**，不是陈旧快照。
- **列表 key 唯一性**：逐处核对（`bucketId`、`bucketId:name`、`month:y:m`、`y-${id}`、`staged-${id}`、哨兵 bucketId 区间与 `NEW_ALBUM_BUCKET_BASE` 错开），未发现重复 key 崩溃风险。
- **备份规则**：`backup_rules.xml` / `data_extraction_rules.xml` 已全量排除 `sharedpref`，会话与账本不会被跨设备迁移带成垃圾数据。
- **`MediaOpCoordinator.emit` 的消息丢弃**：`tryEmit` 在无订阅者/缓冲满时静默丢值（**这是丢弃策略，不是背压**，术语需在注释中写清）；但根组合的订阅早于任何可达的业务点击，正常链路不可达 —— **不作为缺陷登记（原 P9 已撤回）**。

---

## 5. 批次落地矩阵

### 批次一（核心状态机与安全屏障）

**P10 + P2（(γ) 出栈封印）+ P2-Discard + P11(iv) + P12**

| 项 | 落地内容 |
|---|---|
| P10-VM | 九个方法入口 `if (_state.value.submitting) return`（注释写明为兜底网、主线程无锁） |
| P10-UI | ⠇ 菜单按钮、**范围切换按钮**、关闭 X 统一 `enabled = !state.submitting` |
| P10-Nav | `BackHandler` **保持 `enabled = reviewScope != null`**，回调内 `if (submitting) return@BackHandler`；`openScope` 读 VM 状态拦截 |
| P2-Commit | `submitting = true` 提到 `launch` 之前；`finally` 派生 `settledMoveIds` / `settledTrashIds` / `hasAnySettled`；摘除已落盘项；`if (hasAnySettled) history.removeAll { it.action == Keep }`；**本批次保留 `saveStaging`** |
| P2-Discard | `sessionStagedIds` 索引（钩子落在 `restoreStagedInternal`）+ `discardableCount` 口径分离 + 循环内逐条递减、**删除外部归零** |
| P12 | `restoreAllStaged()` 收敛到 `sessionStagedIds` |

**批次依赖（重要）**：`commit().finally` 中 `session.saveStaging(...)` 的移除**必须等批次二 P4b**（`StagingTrash` 自持落盘）落地，否则会出现「内存已清、磁盘仍留旧 id」的窗口（影响低，会被 `imagesByIds` 自然剔除，但它破坏「批次一可独立验证」的前提）。

### 批次二（数据流鲁棒性）

**P4a + P4b**：`runCatchingUnlessCancelled` + 仅 `success(emptyList)` 清快照；`StagingTrash(sessionStore)` 自持落盘 + `restored` 就绪门控；**此时才**从 `commit()` 与 `persist()` 剥离 `saveStaging`。

### 批次三（算法与判据加固）

**P5 + P5′ + P3**：守死 Stage 1 单字节精等；`0L/-1L` 补均值亮度二次校验；修正 `DuplicateFinder` KDoc；`MediaStoreRepository` 收紧为双 `LIKE ?` 并清理死断言。

### 批次四（UI 与交互）

**P1 + P6 + P7**：保留除法公式、交换 `settings_sensitivity_low/high` 映射、修 `AppSettings` KDoc；`standardDirsMissing(existing, ...)`；`destinationKey` 提升为 `Scaffold` 外单一真源并门控 FAB。

### 批次一验收回归清单

1. 部分成功（移动成功 + 回收站被拒）后，↺ 能撤销那条**被拒的** Trash；
2. 提交成功项的账本**不得**被 `undo()` / `discardChanges()` 抹掉；
3. 全拒绝（`hasAnySettled == false`）时撤销键**仍然亮**，且 `discardChanges()` 能回滚本次会话的 Keep；
4. `restart()` 后退出，「丢弃改动(3)」**真的丢 3 项**；
5. 其他范围/其他会话攒下的暂存项，**不得**被本范围的「丢弃改动」虚报（`discardableCount` 口径）或被吞掉；
6. 「全部还原」不越过本会话边界；
7. 提交期间：⠇ 菜单 / 范围切换 / X / 系统返回键**均不产生任何队列变更**，且返回键**不得**穿透关闭 Activity。

---

## 6. 未决项

**无。** 批次一至批次四的实施规范已收敛到可直接编码的粒度；`restart()` 的既有实现与单测经 (iii)/(iv) 论证后**保持不变**，不存在需要改判的既有测试。

---

## 7. 附：本文件与其它文档的关系

| 文件 | 内容 |
|---|---|
| `docs/code-review-2026-10-12.md` | 第一轮审核（C1–C3 / R1–R2 / Nit 组，均已于同轮修复） |
| **`docs/code-review-2026-10-13.md`（本文件）** | 第三轮全量审核 + 七轮对抗复核的最终结论与实施规范 |
| `docs/slidebox-implementation-notes.md` | 参考实现的对照笔记 |

**再次强调：本文件登记的所有规范均处于「待实施」状态，本轮未修改任何代码。**
