package com.phototidy.ui.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phototidy.R
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.ReviewAction
import com.phototidy.ui.components.LibraryImage
import com.phototidy.ui.theme.PhotoCardShape
import com.phototidy.ui.theme.PhotoTidyMotion
import com.phototidy.ui.theme.PhotoTidyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 当前手势指向的动作。None 表示还没越过死区。 */
private enum class SwipeIntent { None, Keep, Trash, Undo }

/**
 * 整理流程的核心：一张可以用四个方向拖动的照片卡片。
 *
 * 手势映射（与底部按钮条一一对应，学会任意一种都能完成操作）：
 *
 *   ←  左滑  保留 / 下一张        绿色
 *   ↑  上滑  移到回收站（排队）    红色
 *   ↓  下滑  撤销上一个动作        中性灰
 *
 * 右滑刻意不绑定动作：这是个整理工具，收藏不属于整理语义。
 *
 * 人体工学上的几个决定：
 *  · **图片用 Fit 而不是 Crop**：整理照片时看不到完整画面就没法做判断，
 *    宁可留黑边也不能裁掉关键内容。
 *  · **阈值取卡片宽高的 28% / 22% 而不是固定 dp**：大屏手机上同样的物理位移对应更多像素，
 *    按比例算才能保证所有尺寸下手感一致。
 *  · **越过阈值时给一次触感反馈**：用户不必盯着屏幕就知道「现在松手会生效」，
 *    眼睛可以全程留在照片上 —— 这是盲操型应用最关键的反馈通道。
 *  · **滑动不打字、不滚动**：整页没有任何可滚动容器，避免手势竞争。
 */
@Composable
fun SwipeablePhotoCard(
    image: MediaImage?,
    nextImage: MediaImage?,
    hapticsEnabled: Boolean,
    sensitivity: Float,
    showNextPreview: Boolean,
    moveTicket: Long,
    moveTarget: MediaAlbum?,
    onAction: (ReviewAction, MediaAlbum?) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    /**
     * 位置有两个来源：手指按下时由 [dragOffset] 同步驱动，松手后由 [settleAnim] 驱动。
     *
     * 为什么不是一个 `Animatable`：它的写入口 `snapTo` 是挂起函数，而 `onDrag` 回调
     * 不是挂起上下文。用 `scope.launch { snapTo() }` 写，等于把每一次位移都推迟到
     * 下一次调度 —— 手指和卡片差一帧，低端机上还能看出迟滞。
     */
    val settleAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }

    val keepColor = PhotoTidyTheme.keep
    val trashColor = PhotoTidyTheme.trash
    val undoColor = PhotoTidyTheme.undo

    // ------------------------------------------------------------------
    // 以下计算必须在「取值时」重新执行。
    // 因为 pointerInput 的手势回调只在 key 变化时重建，如果直接用组合期算好的
    // val，第一次组合时 cardSize 还是 0，阈值就被永久冻成 1px —— 手指一碰就触发动作。
    // ------------------------------------------------------------------
    fun thresholdX(): Float = (cardSize.width * 0.28f / sensitivity).coerceAtLeast(1f)

    fun thresholdY(): Float = (cardSize.height * 0.22f / sensitivity).coerceAtLeast(1f)

    /** 当前应当渲染的偏移：拖动期看手指，其余时间看动画。 */
    fun currentOffset(): Offset = if (dragging) dragOffset else settleAnim.value

    /**
     * 把归一化偏移归到四个语义方向之一。
     * 死区 / 右滑不绑定 → None。这是「方向判定」的唯一真源，
     * 既供 [resolveIntent] 判断是否越过阈值，也供下方绘制反馈（liveIntent）使用，避免两套映射各写一遍。
     */
    fun classifyIntent(px: Float, py: Float): SwipeIntent {
        if (abs(px) < 0.04f && abs(py) < 0.04f) return SwipeIntent.None
        val horizontalDominant = abs(px) >= abs(py)
        // 右滑不绑定任何动作（收藏已移除），水平方向只留「左滑 = 保留」
        if (horizontalDominant) return if (px > 0f) SwipeIntent.None else SwipeIntent.Keep
        return if (py < 0f) SwipeIntent.Trash else SwipeIntent.Undo
    }

    /** 归一化偏移 → 四向语义。 */
    fun intentOf(o: Offset): SwipeIntent =
        classifyIntent(
            (o.x / thresholdX()).coerceIn(-1f, 1f),
            (o.y / thresholdY()).coerceIn(-1f, 1f),
        )

    /** 已越过的进度（0..1）。没越过死区算 0，与 [intentOf] 共用同一套判据。 */
    fun progressOf(o: Offset): Float =
        if (intentOf(o) == SwipeIntent.None) {
            0f
        } else {
            maxOf(
                abs((o.x / thresholdX()).coerceIn(-1f, 1f)),
                abs((o.y / thresholdY()).coerceIn(-1f, 1f)),
            )
        }

    /** 横向拖了多远，用于「下一张」的放大/透明跟随。 */
    fun dragFractionOf(o: Offset): Float =
        (abs(o.x) / cardSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)

    fun resolveIntent(): SwipeIntent {
        val o = currentOffset()
        return if (progressOf(o) >= 1f) intentOf(o) else SwipeIntent.None
    }

    // ---- 组合期只派生「低频」量 ----
    // derivedStateOf 只在结果**变化**时才让读者重组：拖动全程 liveIntent 至多切换一两次，
    // armed 只在越过阈值那一刻翻转。高频量（位移、旋转、描边透明度、印章进度、
    // 下一张的缩放/透明）一律留到 graphicsLayer 的 lambda 里读 —— 只失效图层，不重组。
    // 否则整张卡片（含两个 AsyncImage、带阴影的 Surface、三个印章）会以 60~120Hz 重组。
    //
    // key 必须带上 sensitivity：局部函数是按值捕获它的，不加 key 的话
    // 用户改了灵敏度之后这里会一直用旧阈值。
    val liveIntent by remember(sensitivity) { derivedStateOf { intentOf(currentOffset()) } }
    val armed by remember(sensitivity) { derivedStateOf { progressOf(currentOffset()) >= 1f } }

    var wasArmed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed && !wasArmed) {
            wasArmed = true
            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!armed) {
            wasArmed = false
        }
    }

    // 换片：复位所有手势状态
    LaunchedEffect(image?.key) {
        settleAnim.snapTo(Offset.Zero)
        dragging = false
        dragOffset = Offset.Zero
        busy = false
    }

    /**
     * 从当前位置动画到 [target]。
     *
     * `dragging = false` 必须排在 `snapTo` **之后**：万一下一次调度被让出，
     * 这一帧仍然由 [dragOffset] 驱动，画面不会跳回动画的旧值。
     */
    suspend fun settleTo(target: Offset, spec: FiniteAnimationSpec<Offset>) {
        settleAnim.snapTo(currentOffset())
        dragging = false
        settleAnim.animateTo(target, spec)
    }

    suspend fun flyOutTo(target: Offset) {
        busy = true
        runCatching { settleTo(target, PhotoTidyMotion.dismissal()) }
        busy = false
    }

    // 外部触发的「移动到相册」：卡片往下滑出屏幕，用空间位移表达「放进文件夹」
    LaunchedEffect(moveTicket) {
        if (moveTicket == 0L || moveTarget == null) return@LaunchedEffect
        // busy 为真说明上一张卡片的飞出动画还在跑：先等它结束再处理本次「移动」，
        // 而不是直接 return —— 直接 return 会静默吞掉 onAction(Move)，并让 moveTarget 卡在非空态。
        if (busy) snapshotFlow { busy }.first { !it }
        flyOutTo(Offset(currentOffset().x, cardSize.height * 1.35f))
        onAction(ReviewAction.Move, moveTarget)
    }

    val gestureEnabled = image != null && !busy

    Box(
        modifier = modifier
            .onSizeChanged { cardSize = it }
            .pointerInput(image?.key, gestureEnabled) {
                if (!gestureEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        // 从动画的当前位置接管，然后交给同步拖动
                        dragOffset = currentOffset()
                        dragging = true
                    },
                    onDragCancel = {
                        scope.launch { settleTo(Offset.Zero, PhotoTidyMotion.spatialFast()) }
                    },
                    onDragEnd = {
                        scope.launch {
                            when (resolveIntent()) {
                                SwipeIntent.None -> settleTo(
                                    Offset.Zero,
                                    PhotoTidyMotion.spatialFast(),
                                )

                                SwipeIntent.Keep -> {
                                    flyOutTo(Offset(-cardSize.width * 1.35f, currentOffset().y + 40f))
                                    onAction(ReviewAction.Keep, null)
                                }

                                SwipeIntent.Trash -> {
                                    flyOutTo(Offset(currentOffset().x, -cardSize.height * 1.35f))
                                    onAction(ReviewAction.Trash, null)
                                }

                                SwipeIntent.Undo -> {
                                    // 撤销不该「飞出」—— 它是「把刚才那张拉回来」，
                                    // 用带弹性的归位更符合这个心理模型
                                    settleTo(Offset.Zero, PhotoTidyMotion.spatialBouncy())
                                    onUndo()
                                }
                            }
                        }
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        // 就地累加：这里不是挂起上下文，也就没有逐事件 launch 一个协程的开销，
                        // 位移与手指同一帧生效
                        dragOffset += delta
                    },
                )
            },
    ) {
        // ---------- 下一张：在后面微微放大 ----------
        // 即使设置里关掉了预览也照样组合这一层 —— 它顺带承担了「下一张」的解码：
        // 关掉预览时只是用绘制期 alpha 藏起来，滑到下一张时已经命中缓存，
        // 不会出现「空卡片 → 淡入」这一下。
        if (nextImage != null) {
            Surface(
                shape = PhotoCardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 18.dp)
                    .graphicsLayer {
                        val f = dragFractionOf(currentOffset())
                        val scale = 0.93f + 0.07f * f
                        scaleX = scale
                        scaleY = scale
                        alpha = if (showNextPreview) 0.45f + 0.55f * f else 0f
                    },
            ) {
                LibraryImage(
                    image = nextImage,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---------- 当前这张 ----------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val o = currentOffset()
                    translationX = o.x
                    translationY = o.y
                    // 轻微倾斜：让拖动有「实体被拨动」的手感；0.045 是实测不晕的系数
                    rotationZ = o.x * 0.045f
                },
        ) {
            Surface(
                shape = PhotoCardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                LibraryImage(
                    image = image,
                    contentDescription = stringResource(R.string.cd_current_photo),
                    contentScale = ContentScale.Fit,
                    crossfade = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 方向高亮描边：颜色即语义，不用读文字也能判断
            if (liveIntent != SwipeIntent.None) {
                val edgeColor = when (liveIntent) {
                    SwipeIntent.Keep -> keepColor
                    SwipeIntent.Trash -> trashColor
                    SwipeIntent.Undo -> undoColor
                    SwipeIntent.None -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(PhotoCardShape)
                        .border(
                            width = 3.dp,
                            color = edgeColor,
                            shape = PhotoCardShape,
                        )
                        // 透明度连续变化，只能放在绘制期读 —— 放进组合期会让整张卡片逐帧重组
                        .graphicsLayer { alpha = 0.3f + 0.7f * progressOf(currentOffset()) },
                )
            }

            // 四向印章
            SwipeStamp(
                text = stringResource(R.string.stamp_keep),
                color = keepColor,
                rotationDeg = -12f,
                visible = liveIntent == SwipeIntent.Keep,
                progress = { progressOf(currentOffset()) },
                armed = armed && liveIntent == SwipeIntent.Keep,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp),
            )
            SwipeStamp(
                text = stringResource(R.string.stamp_trash),
                color = trashColor,
                rotationDeg = 0f,
                visible = liveIntent == SwipeIntent.Trash,
                progress = { progressOf(currentOffset()) },
                armed = armed && liveIntent == SwipeIntent.Trash,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            )
            SwipeStamp(
                text = stringResource(R.string.stamp_undo),
                color = undoColor,
                rotationDeg = 0f,
                visible = liveIntent == SwipeIntent.Undo,
                progress = { progressOf(currentOffset()) },
                armed = armed && liveIntent == SwipeIntent.Undo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp),
            )
        }
    }
}

@Composable
private fun SwipeStamp(
    text: String,
    color: Color,
    rotationDeg: Float,
    visible: Boolean,
    progress: () -> Float,
    armed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.92f),
        modifier = modifier.graphicsLayer {
            // 进度在绘制期读：透明度与缩放连续跟随手指，但不引起重组
            val p = progress()
            alpha = (p * 1.5f).coerceIn(0f, 1f)
            rotationZ = rotationDeg
            scaleX = 0.86f + 0.14f * p
            scaleY = 0.86f + 0.14f * p
        },
    ) {
        Text(
            text = if (armed) stringResource(R.string.stamp_armed) else text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
