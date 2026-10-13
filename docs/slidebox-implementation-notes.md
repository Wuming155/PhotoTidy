# Slidebox 3.18 参考实现笔记

> 来源：`参考项目/Slidebox_3.18.apk`（SHA-256 `6782aff672aab95562cb214ac229e0b5f1512d59c1db49f8bdd552bcac2e4c50`）
>
> 分析方式：REA（jadx-headless-mcp 0.7.1，审计版 JAR）静态反编译 + apktool 资源解码。
> 代码为 R8 混淆产物（方法名 `p3`/`q3`/`j3` 这类短名），语义依据 = 反编译方法体 + 未混淆的
> 字符串资源 + Manifest 声明。**所有结论均为静态观察，不代表运行时行为**；标注
> 「推断」的地方是证据不足下的合理推测，实现时需自行验证。

包级事实（Evidence `ev_76468169…`）：

| 项 | 值 |
| --- | --- |
| 包名 / 版本 | `co.slidebox` 3.18 (318) |
| minSdk / targetSdk | 30 / 35 |
| 关键权限 | `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO`、`MANAGE_MEDIA`、`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED` |
| 技术栈 | View 体系（非 Compose）+ Room + WorkManager + Picasso + Firebase 全家桶 + Billing 7.0 + AdMob |

Slidebox 与 PhotoTidy 的共同骨架：**滑动只改内存队列，点「应用」才提交，提交走一次
PendingIntent 系统授权**（`OrganizeApplyActivity.onCreate` 反编译证实：按
`EXTRA_KEY_INPUT_GALLERY_COMMIT_ID` 取回待提交对象，弹「系统将显示确认提示」）。
`MANAGE_MEDIA` 权限让部分路径可以跳过系统确认框，这是 PhotoTidy 刻意不跟随的。

---

## 1. 整理时新建相册

### 入口与 UI

- 整理页底部移动条（Slidebox 叫 toolbox，文案 `organize__toolbox_header_move_to_album`
  「转移到相册..」）里有一项 `organize__toolbox_action_item_new_album`「新相册..」。
- 点击弹 `OrganizeSaveToAlbumPopupActivity`（透明弹窗），标题
  「转移到新相册..」，一个输入框 + 一个复选框 + 「创建」按钮：

  | 字符串 | 文案 |
  | --- | --- |
  | `organize_save_to_album_popup__title` | 转移到新相册.. |
  | `organize_save_to_album_popup__album_name_placeholder` | 相册名称 |
  | `organize_save_to_album_popup__checkbox_label` | 将在你的画廊中创建相册 |
  | `organize_save_to_album_popup__save_button` | 创建 |
  | `...__status_loading / success / error` | creating.. / created! / error! |

- 相册页有独立入口 `AlbumCreatePopupActivity`（`library_menu__album_create_title`
  「创建新相册」），复选框文案 `library_album_create_popup__create_android_album_label`
  「将在安卓画廊中创建相册」——两个弹窗共用同一套交互。

### 数据模型：双「卷（volume）」

反编译显示相册不是简单的目录名，而是挂在「卷」下：

```java
// AlbumCreatePopupActivity.onCreate
this.f10578d0 = getIntent().getStringExtra("EXTRA_KEY_INPUT_VOLUME_NAME");
this.f10577c0 = App.i().K(this.f10578d0);   // 取该卷下已有相册名集合（Set<String>）
```

Slidebox 维护「应用私有文件夹」和「安卓画廊相册」两个卷（首页分组
`home__group_folders`「文件夹」/`home__group_albums`「相册」），复选框
「将在你的画廊中创建相册」就是选择目标卷。**PhotoTidy 只有 MediaStore
一种存储，不需要这个抽象**——直接映射为「选目标父目录（DCIM/ 或 Pictures/）」。

### 创建与校验流程（反编译 `q3`/`p3`）

```java
// OrganizeSaveToAlbumPopupActivity.q3 —— 点「创建」先校验
protected void q3(String name, boolean checkbox) {
    if (name.length() == 0)            { u3(); }                       // 空名 → 报错
    else if (existingNames.contains(name)) { w3(); u3(); }             // 重名 → 警告 + 报错
    else                               { x3(); v3(); }                 // loading → 提交
}

// OrganizeSaveToAlbumPopupActivity.p3 —— 校验通过后创建
protected void p3(String name, boolean checkbox) {
    z3(getString(...));                     // 显示 creating..
    K0.j album = App.i().m(volume, name);   // 同步建相册，返回相册对象
    D3();                                   // 收起 loading
    B3(getString(...));                     // 显示 created!
    C3(album);                              // 带结果返回，整理页立刻把当前照片投进新相册
}
```

要点：

1. **重名校验在客户端先做**（拿卷内已有相册名集合 `contains` 判断），重名直接拦下，
   不等系统报错。
2. 创建是**同步**的（方法体内没有异步回调），成功后**立即返回结果给整理页**，
   当前照片直接投递进新相册——用户感知是「新建相册」和「移动照片」一步完成，
   不用再点一次移动条。
3. loading/成功/失败三态直接显示在弹窗里（creating.. → created!），不跳页。
4. 复选框布尔值传进了 `p3` 但方法体内未读取（混淆优化后），目标卷应该在更深的
   `App.i().m(volume, name)` 里按卷参数区分——推断：勾选 = 在画廊卷创建
   （MediaStore 建目录），不勾 = 在应用私有卷创建。

### 对 PhotoTidy 的落地建议

- `AlbumQuickMoveBar` 尾部加「新相册…」项 → 弹输入框（Material3 `AlertDialog` 即可）；
- 重名校验用现有 `LibraryUiState.albums` 的名字集合；
- 创建 = `MediaStore` 在 `Pictures/<名字>` 或 `DCIM/<名字>` 下建目录
  （`RELATIVE_PATH` 首次插入时自动落目录，或在会话提交时随首批移动一起建）；
- 创建成功后把该相册追加进 `moveTargets` 并直接对当前卡片触发 `moveTo(album)`，
  复刻「建完即投递」的节奏。

---

## 2. 退出整理确认（OrganizeCancelActivity）

### 触发与文案

整理中按返回时，若有待提交改动，弹透明确认页（`Theme.Transparent`）：

| 字符串 | 文案 |
| --- | --- |
| `organize_cancel__title` | 是否取消整理？ |
| `organize_cancel__description_format` | %d个待处理更改 |
| `organize_cancel__action_discard_button_format` | 取消更改 (%d) |
| `organize_cancel__action_dismiss_button` | Dismiss（继续整理） |

### 实现（反编译）

退出确认不是读 ViewModel，而是**围绕「待提交对象」构建**——与提交页、完成页共用
同一个按 id 取回的 commit 对象：

```java
// OrganizeCancelActivity.onCreate
String commitId = getIntent().getStringExtra("EXTRA_KEY_INPUT_GALLERY_COMMIT_ID");
c1.a commit = App.h().c0(this, commitId);      // 按 id 取回待提交对象
if (commit.p()) { /* 展示 */ } else { finish(); } // 对象已失效就直接退出

// OrganizeCancelActivity.j3 —— 填充数字
int moves = commit.k();                          // 待转移张数
int deletes = commit.j();                        // 待删除张数
view.h(moves, deletes);                          // 「转移 X 张 / 删除 Y 张」
view.i(moves + deletes);                         // 「N 个待处理更改」
view.j(moves + deletes);                         // 「取消更改 (N)」按钮文案

// OrganizeCancelActivity.q0 —— 点 Dismiss（继续整理）
analytics.a("organize_cancel_dismiss", null);
finish();                                        // 只关弹窗，会话原样保留
```

要点：

1. 数字全部**派生自待提交对象**（转移 k()、删除 j() 两个计数器），UI 不自己数——
   和 PhotoTidy 把 `trashedCount/movedCount` 派生自队列是同一个思路。
2. 「继续整理」= 纯关闭，什么都不动；「取消更改」= 丢弃待提交队列。
3. 埋点事件名：`organize_cancel_display` / `organize_cancel_dismiss`（丢弃按钮另有事件）。

### 对 PhotoTidy 的落地建议

PhotoTidy 现状：退出整理页即保留会话（重启可恢复），**比 Slidebox 安全**；
缺的是「放弃这些改动」的显式入口——用户想丢弃排队中的改动只能逐张撤销。
建议：整理页返回时若有 `hasPending`，弹确认框，给「继续整理 / 保留退出 / 丢弃改动」
三个动作；「丢弃」清空 `staging` + `pendingMoves` + `history` 并落盘空列表，
数字归零后正常退出。文案数字直接用 `ReviewUiState.pendingTotal`。

---

## 3. 提交前汇总确认（完成页 → 应用页）

### 流程与文案

滑完一个范围后进入 `OrganizeFinishActivity`（完成页）：

| 字符串 | 文案 |
| --- | --- |
| `organize_finished__title` | 完成！ |
| `organize_finished__done_subtitle_no_more_photos` | 没有需要整理的照片 |
| `organize_finished__apply_summary_move_pending_format` | 转移%d张照片 |
| `organize_finished__apply_summary_delete_pending_format` | 删除%d张照片 |
| `organize_finished__apply_subtitle_apply_pending_changes` | 确认这些更改并在画廊中生效。 |
| `organize_finished__apply_action_button` | 确认更改 |
| `organize_finished__done_button` | 完成 |
| `organize_apply__title` | 是否确认更改？ |
| `organize_apply__description` | 系统将显示确认提示。 |
| `organize_success_popup__title` | 操作成功！ |
| `organize_success_popup__button_write_review` | （求评价按钮） |

### 实现（反编译）

```java
// OrganizeFinishActivity.onCreate
c1.a commit = App.h().c0(this, commitId);   // 同一个待提交对象
commit.d(this);                             // 注册完成回调
presenter.i(commit.k(), commit.j());        // 转移数 / 删除数 → 汇总文案
analytics.a("organize_finish_display", null);

// OrganizeFinishActivity.l2 —— 「确认更改」按钮
if (commit.m()) {                 // 还有待提交改动
    n3();                         // → 进 OrganizeApplyActivity 执行
} else {
    analytics.a("organize_finish_dismiss", null);
    m3();                         // 没有改动 → 直接「完成」退出
}
```

`OrganizeApplyActivity`（Evidence：onCreate 反编译）按 commit id 取对象、
弹「是否确认更改？系统将显示确认提示」、用两个 `ActivityResultLauncher`
（字段 `f10821S/f10822T`）发起 PendingIntent 授权——一张全屏进度页包住
系统确认框，成功后进 `OrganizeSuccessActivity`。

要点：

1. **汇总确认页的数字与退出确认页同源**（同一个 commit 的 k()/j()），
   三个页面（取消/完成/应用）共享一份数据，不会出现数字对不上的情况。
2. 「完成」和「应用」是两个动作：没有待提交改动时完成页只给「完成」；
   有改动才出现「确认更改」。
3. 应用过程是**透明 Activity 盖住系统确认框**（`Theme.Transparent` +
   `noHistory`），失败/拒绝各有兜底页。
4. 免费版限制整理次数（订阅卖点 `purchase__benefits_bullet_unlimited_organization`
   「无限量整理」），PhotoTidy 无此概念。

### 对 PhotoTidy 的落地建议

PhotoTidy 现状：点「提交」直接执行（`ReviewViewModel.commit()`），只有执行中的
`submittingLabel`，**没有提交前的静态汇总**。建议在 `commit()` 入口加一步确认
（Dialog 或 BottomSheet）：列出「转移 X 张到相册 / 回收站 Y 张」，确认后才走
现有提交流程。数据全部现成：`pendingMoves.size` + `stagedCount`。
完成后可复用现有 snackbar 或加一个轻量成功页（不必做求评价）。

---

## 4. 截图整理范围

### 证据

- 首页磁贴：`home__tile_item_title_screenshots`「Screenshots」，与
  Videos / Long Videos / Duplicates / Camera 并列。
- 整理范围标题：`organize__title_screenshots`「SCREENSHOTS」。
- 关键常量：`bucket_display_name__screenshots` = **`"Screenshots"`**（字符串资源里
  硬编码的桶显示名，各语言资源里不变）。
- 没有独立的 Screenshots Activity/类（类名搜索无命中），功能挂在主页的
  通用磁贴 + 整理范围机制上。

### 实现方式（静态证据 + 推断）

整理范围（未分类/截图/视频）没有独立查询类，说明是**同一个查询通道按参数分流**。
`bucket_display_name__screenshots` 硬编码为 `"Screenshots"` 强烈暗示筛选条件是
`MediaStore.Images.BUCKET_DISPLAY_NAME == "Screenshots"`（即 AOSP 截图目录的
标准桶名），而非 `RELATIVE_PATH` 前缀匹配。**推断**：查询形如
`WHERE bucket_display_name = 'Screenshots'`，首页磁贴与整理范围共用这一条查询，
磁贴上显示该范围未整理张数。

已知风险（供 PhotoTidy 设计时规避）：部分 ROM 会把截图桶名本地化（如「截图」），
按显示名过滤会漏。PhotoTidy 已有 `relativePath` 字段，建议用
`RELATIVE_PATH LIKE 'Pictures/Screenshots%'` 匹配，比桶名可靠。

### 对 PhotoTidy 的落地建议

新增一个 `MediaScope.Screenshots`：概览扫描时顺带统计
`relativePath` 以 `Pictures/Screenshots` 开头的数量（`LibraryOverview` 已有
相册/月份计数结构，加一个标量即可），首页加一张磁贴、整理页范围选择器加一项。
实现成本很低，复用现有分页/账本机制。

---

## 5. 重复照片检测

### 证据与限制

- 首页磁贴：`home__tile_item_title_duplicates`「Duplicates」/「重复」，带
  `premium_tag`「高级」标。
- **没有**任何本地算法相关的类名/字符串证据（无 hash、perceptual、similarity
  等关键词命中；类名搜索 `uplicate` 无独立类）。
- Slidebox 有完整的云基础设施（`google_storage_bucket` = appspot.com、
  账号注册/登录、`json.api.v1.*` API 协议、Firebase Functions），
  重复检测跑在客户端还是服务端**无法静态判定**。

### 能确认的部分

1. 入口是首页磁贴（与截图/视频同级），点进后是独立的整理/清理流程
   （无独立 Activity 名，说明复用通用容器，推断为结果列表 + 批量删除）。
2. 它是付费功能（premium 标 + 「无限量整理」付费卖点）。
3. 首页磁贴必然需要「有 N 组重复」的计数——这个计数若在客户端算，
   扫库时就要做分组；若在服务端算，磁贴数字来自云查询。两者静态证据都不足。

### 对 PhotoTidy 的落地建议

不跟随「云检测/付费」形态，做纯本地：扫描概览时按「文件大小」粗分组
（SQL `GROUP BY _size HAVING COUNT(*) > 1`，零成本），有候选组才显示入口；
进组后按需计算感知哈希（如 aHash/dHash，只对大小相同的候选算）确认相似度，
展示成组视图让用户勾选保留一张、其余进回收站（复用现有暂存提交管线）。
工程量大，建议排期在 1–4 项之后。

---

## 6. 回收站管理（系统回收站 + 整理中回收站）

Slidebox 有**两个**回收站概念，PhotoTidy 目前只有系统回收站页。

### 6a. 系统回收站（主界面「更多」里的 Trash 菜单）

| 字符串 | 文案 |
| --- | --- |
| `trash_menu_view_title` / subtitle | View Trash / View trashed photos |
| `trash_menu_recover_title` / subtitle | Recover Trash / Recover all trashed photo back to unsorted |
| `trash_menu_empty_title` / subtitle | Empty Trash / Permanently delete all trashed photos |
| `trash_recover_all_button` | R E C O V E R  A L L  P H O T O S |
| `trash_delete_all_button` | D E L E T E  A L L  P H O T O S |
| `trash_recover_selected_button_format` | R E C O V E R  %d  P H O T O S |
| `trash_recover_popup_description_format` | %d photos will be considered unsorted. |
| `trash_empty_popup_title_format` | Delete %d photos permanently? |
| `trash_empty_popup_description_format` | %d photos in trash will be deleted permanently from your device. |
| `trash_emptied_popup_title` / description | PHOTOS DELETED! / Photos have been permanently deleted from your phone. |
| `trash_emptied_popup_rate_button` | give us five stars! |

要点：

1. **两个批量快捷动作**：全部还原 + 清空回收站，和多选批量（RECOVER/DELETE %d）
   并存，都走确认弹窗（含张数格式化文案）。
2. 还原语义 = 「回到未分类」（`will be considered unsorted`）——与 PhotoTidy
   还原后 `reviewed.unmark()` 的处理一致。
3. 清空成功后有强反馈弹窗（PHOTOS DELETED!）+ 求评价。
4. 删除/还原的执行仍走系统授权（`createTrashRequest`/`createDeleteRequest`
   语义），与 PhotoTidy 相同。

### 6b. 整理中回收站（OrganizeTrashActivity）

整理页内点回收站图标进入（`instructions__item_trash_empty`
「点击回收站标志进行删除」）。反编译：

```java
// OrganizeTrashActivity.onCreate —— 整理会话把「本会话已排队的回收站」传进来
ArrayList trashed = (ArrayList) getIntent()
        .getSerializableExtra("EXTRA_KEY_ASSET_TRASH_LIST");   // 会话内暂存列表
g1.a interactor = App.h().h0(trashed);                          // 构建会话内回收站
interactor.d(this);                                             // 注册结果回调

// z0 —— 全部还原：发起 PendingIntent 授权（launcher + interactor.n()）
g3(this.f10847Q, this.f10849S.n());

// m3 / n3 —— 操作完把结果列表带会整理页
intent.putExtra("EXTRA_KEY_RESULT_DELETED_ASSET_LIST",   deleted);
intent.putExtra("EXTRA_KEY_RESULT_RECOVERED_ASSET_LIST", recovered);
finish();
```

要点：

1. 整理中回收站展示的是**会话暂存区**（还没真正进系统回收站的内存列表），
   不是系统回收站——用户在提交前还能反悔。
2. 操作结果（删了哪些/还原了哪些）**通过 Intent 结果列表回传**，整理页据此
   更新自己的队列与计数。
3. 「还原」在整理语境下= 从暂存区撤回、回到未整理队列（与主界面回收站
   语义一致）。

### 对 PhotoTidy 的落地建议

- **主回收站**：`TrashScreen` 已有勾选 + 批量还原/删除 + 全选，补两个快捷项
  「全部还原 / 清空回收站」（带现有 `DeleteConfirmDialog` 风格的确认框，
  文案数字用 `trashCount`）。清空 = `deleteFromSystemTrash(全部已加载页)`，
  需先 `loadAllTrash()`（已有）。
- **整理中回收站**：PhotoTidy 的暂存区目前只能在卡片上逐张撤销（下滑）。
  参照 Slidebox 在整理页给「回收站」入口，弹一个已排队照片网格（数据 =
  `staging.items`），支持点选还原（= `undo` 到该张）——数据全部现成，
  只需要 UI。这能显著改善「滑了几十张后发现误删」的体验。

---

## 证据索引

| 结论 | 证据 |
| --- | --- |
| 包/权限/Activity 清单 | Evidence `ev_76468169e243cd41f140714d0aa9958fda333025029ffd89c08273e235b5fa3a` |
| OrganizeScreen 成员清单 | `ev_91039f7bed0162a4b057bd9a4285d24562fbc3360e0d7f5dfd3ce68950658812` |
| OrganizeApplyActivity.onCreate | 会话内文件 `m_apply_onCreate.txt`（Evidence `ev_76468169` 同目标） |
| OrganizeApplyActivity.onResume | `ev_2ef1b0893e2cac2f9517d02f0f353e837565a4a0a42540181a13671f8af57c92` |
| 各弹窗/取消/完成/回收站方法体 | `/tmp/bodies/*.txt`（本次会话 REA 输出，可随时用 REA CLI 重放） |
| 字符串文案 | apktool 解码 `res/values-zh-rCN/strings.xml` 与 `res/values/strings.xml`（shell 工具，非 REA Evidence） |

复现命令（需 `REA_JADX_MCP_JAR=C:\Users\baiyun\tools\jadx-headless-mcp-0.7.1-all.jar`）：

```sh
npx -y rea-agents@6.4.0 inspect-android-package  "参考项目/Slidebox_3.18.apk"
npx -y rea-agents@6.4.0 inspect-android-class    "参考项目/Slidebox_3.18.apk" co.slidebox.ui.organize.OrganizeScreen
npx -y rea-agents@6.4.0 inspect-android-method   "参考项目/Slidebox_3.18.apk" co.slidebox.ui.organize_cancel.OrganizeCancelActivity onCreate
```
