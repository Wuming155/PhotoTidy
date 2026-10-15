# AGENTS.md

PhotoTidy：本地优先的 Android 照片 / 视频整理器。单模块 `:app`，包名 `com.phototidy`。
核心语义：滑动只改内存队列，点「提交」时由 `MediaOpCoordinator` 走**一次**系统授权批量落盘。
全仓注释、文案、文档、提交信息均为**中文**，保持一致。

## 构建与验证

前置：JDK 17+、Android SDK Platform 36、Build Tools 36.x。`local.properties` 的 `sdk.dir` 已指向本机 SDK（该文件被 gitignore）。
Gradle wrapper 锁在 8.14.5，`distributionUrl` 走腾讯镜像（内网/海外网络可能需改镜像）。

```bash
./gradlew :app:testDebugUnitTest                     # 唯一的测试入口（JVM 单测，无 androidTest）
./gradlew :app:testDebugUnitTest --tests "com.phototidy.ui.review.ReviewViewModelTest"  # 跑单个类
./gradlew :app:assembleDebug                         # debug 包，applicationId 带 .debug 后缀
./gradlew :app:lintDebug                             # 静态检查
./gradlew :app:assembleRelease                       # R8 + 资源收缩，需签名配置
```

- 改完代码至少跑 `:app:testDebugUnitTest`；动了 UI / 资源再补 `:app:lintDebug`。
- Windows 下 `./gradlew`（Git Bash）或 `gradlew.bat` 都可用。
- Release 签名由 `release-signing.properties`（gitignore，本机已存在）驱动 `app/build.gradle.kts` 的 signingConfigs；缺文件时会用空口令，release 构建失败。`release-key.jks` 同样不入库。

## 架构

- **依赖容器是 `Graph`**（`app/src/main/java/com/phototidy/PhotoTidyApp.kt`）：手写单例 + `by lazy`，**刻意不用 Hilt/Koin**。新增全局依赖加在这里。
- `core/media`：`MediaStoreRepository`（MediaStore 访问）、`MediaOpCoordinator`（写操作授权协调）、`ReviewedLedger`（「已整理」账本）、`StagingTrash`（整理中回收站）、`SessionStore`（会话快照）。`core/` 不出现 UI 文案。
- `ui/`：MVVM。入口 `MainActivity` → `ui/PhotoTidyRoot.kt`（导航 + ≥600dp 侧栏切换）。整理状态机核心是 `ui/review/ReviewViewModel`，分页窗口是 `ui/review/ReviewQueueWindow`。
- 无第三方数据库 / DI / 网络；持久化全部走应用私有 `SharedPreferences`（`SessionStore`）。

## 硬约束

- **无网络权限，这是产品卖点**：绝不添加 `INTERNET`。重复照片检测（dHash）也必须纯本地。
- **媒体写操作只能走系统授权**：`createWriteRequest` / `createTrashRequest` / `createDeleteRequest` + `MediaOpCoordinator`。不要直接 `resolver.update/delete` 绕过授权流程。
- **文案**：UI 字符串一律进 `res/values/strings.xml`（目前只有默认中文 `values/`）；带参数用 `%1$s` / `%1$d` 位置占位符，数量词用 `<plurals>`，不要 `getString` 拼接。`core/` 只能经 `StringProvider` 取文案（为 JVM 单测解耦 `Resources`）。
- `minSdk 31 / compileSdk 36 / targetSdk 36`。targetSdk 36 下大屏会忽略 `screenOrientation`，因此清单刻意不声明，布局靠 `PhotoTidyRoot` 自适应。
- 协程：用 `util/Coroutines.kt` 的 `runCatchingUnlessCancelled`，不要用 `runCatching` 吞掉 `CancellationException`（取消是流程控制，不是失败）。

## 测试

- 全部是 JVM 单测（`app/src/test`），无 Robolectric、无设备测试。`testOptions.unitTests.isReturnDefaultValues = true` 让 stub 版 `android.jar` 返回默认值而非抛异常。
- **`org.json` 在 JVM 是桩，不可测**：需要单测的编解码逻辑写成纯 Kotlin（参考 `SessionStore.kt` 里的 `encodeReviewedBatch` / `decodeReviewedBatch`）。
- ViewModel 测试用 `Dispatchers.setMain(UnconfinedTestDispatcher())`，依赖用手写 Fake（`FakeSessionStore` / `FakeMediaRepo` / `TestStringProvider`），不构造 `Context`。
- `com.phototidy.verification.AuditVerificationTest` 是代码审核结论的回归用例（按 `Issue N` 命名）。修 bug 时在对应 `*Test` 或此处补回归。

## 参考文档

- `docs/code-review-2026-10-12.md`、`docs/code-review-2026-10-13.md`：历史代码审核 + 修复记录。**改 `ReviewViewModel` / 状态机 / 账本前先读**，里面解释了现有反直觉设计的来龙去脉。
- `docs/slidebox-implementation-notes.md`：参考实现 Slidebox 的对照笔记（含 REA 复现命令）。
- `参考项目/`、`verify-shots/`、`.workbuddy/` 已被 gitignore；`tools/make_icon.py` 从 `design/icon-source.png` 生成启动图标（硬编码绝对路径，需 Pillow）。
