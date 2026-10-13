package com.phototidy.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.core.model.MediaImage
import com.phototidy.ui.components.EmptyState
import com.phototidy.ui.components.LibraryImage
import com.phototidy.ui.components.photosLabel
import com.phototidy.ui.components.selectedLabel
import com.phototidy.ui.theme.PhotoTidyTheme
import com.phototidy.util.sumReadableSize

/**
 * 回收站页。
 *
 * 只做一件事：管理**系统回收站**里的照片 —— 还原，或者彻底删除。
 *
 * 整理过程中「排队进回收站」的那些照片不在这里出现。它们还没落到系统相册，
 * 随时能在整理页撤销，属于整理会话的中间状态；而这里的每一张都已经真的交出去了。
 * 一个页面只对应一种事实，用户不用猜「这到底删没删」。
 *
 * 列表走**分页加载**：回收站可能有几千张，一次性读完再渲染会拖慢进入这一页的速度。
 * 滚到底部才去取下一页。
 *
 * 多选支持两种加选方式：
 *  · **长按后拖动划选**：手指压住任意一格再滑动，沿途的格子按起手那一格的状态
 *    统一加选或统一减选 —— 「这一片全要」不必逐张点。
 *  · **全选**：选中回收站里的每一张。列表是分页的，所以全选会先把剩余页读完再一次性选中，
 *    绝不把「已加载的那几页」冒充成「全部」。
 */
@Composable
fun TrashScreen(
    systemTrash: List<MediaImage>,
    trashCount: Int,
    onRestore: (List<MediaImage>) -> Unit,
    onDelete: (List<MediaImage>) -> Unit,
    onLoadMore: () -> Unit,
    onLoadAll: () -> Unit,
    loadingMore: Boolean = false,
    loadError: Boolean = false,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf<List<MediaImage>?>(null) }
    /** 「全选」点了但列表还没读完：等补全完成后再兑现选中。 */
    var selectAllPending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    /**
     * 按下的批量快捷动作。
     *
     * 必须先与「全选」共用同一套补页机制：[loadAllTrash] 是分页的，
     * 直接对「已加载的那几页」执行就等于「清了一部分却说清空了」——
     * 那比没有这个功能更危险（用户以为回收站空了，其实里面还躺着几百张）。
     */
    var bulkAction by remember { mutableStateOf<BulkAction?>(null) }
    /**
     * 每格当前在根坐标系里的位置，只给划选的命中测试用。
     *
     * 故意是普通可变 map 而不是 SnapshotStateMap：它随滚动不断变化，
     * 一旦变成可观察状态就会让整张网格每帧重组 —— 而它只在拖动回调里被读，
     * 根本不需要参与重组。
     */
    val tileBounds = remember { HashMap<Long, Rect>() }
    /** 正在进行的划选按哪种模式作用；null = 没有在划选。 */
    var paint by remember { mutableStateOf<PaintMode?>(null) }

    /**
     * 选中状态的高频写入口是划选：拖动期间每个事件帧都在改 [selectedIds]。
     *
     * 刻意**不**在组合期把选中的照片 filter 成一份列表 —— 那会让本函数每个拖动帧
     * 都做一次 O(n) 扫描 + 列表分配，且整页跟着每帧重组。计数改用选中集的 size
     * （O(1)），完整列表推迟到点击「还原 / 彻底删除」那一刻再算（见 [selectedList]）。
     */
    val selectedCount by remember { derivedStateOf { selectedIds.size } }
    val hasMore = systemTrash.size < trashCount
    val allLoaded = systemTrash.size >= trashCount
    // 「全选了吗」的检查本身是 O(n)（提前短路，未全选时开销极小）：不派生的话，
    // 这个检查会把每帧的选中写入都放大成整页重组
    val allSelected by remember(systemTrash, trashCount) {
        derivedStateOf {
            allLoaded && systemTrash.isNotEmpty() && systemTrash.all { it.id in selectedIds }
        }
    }

    // size 能当计数用的前提是「selectedIds ⊆ 当前列表」。每个写入点都只放当前列表
    // 里的 id（格子 toggle / 全选 / 清空），但列表会在翻页、还原、刷新时被整体替换 ——
    // 这里在每次列表变化后把选中集收敛一次，把幽灵 id 剔掉。幂等：只在真的出现
    // 不在列表里的选中时才重建集合。
    LaunchedEffect(systemTrash) {
        val ids = systemTrash.mapTo(HashSet()) { it.id }
        if (!ids.containsAll(selectedIds)) {
            selectedIds = selectedIds intersect ids
        }
    }

    /** 完整的选中列表只在事件回调里需要：点击时现算，不进组合期。 */
    fun selectedList(): List<MediaImage> = systemTrash.filter { it.id in selectedIds }

    /** 把当前手指命中的格子按划选模式处理。 */
    fun paintAt(root: Offset) {
        val mode = paint ?: return
        val hit = tileBounds.filterValues { it.contains(root) }.keys
        if (hit.isEmpty()) return
        selectedIds = when (mode) {
            PaintMode.Select -> selectedIds + hit
            PaintMode.Deselect -> selectedIds - hit
        }
    }

    // 「全选」在分页场景下要先补全剩余页。这里同时负责收口：
    // 只要还挂着 pending 就持续补，直到读完（兑现全选）、失败（放弃，不假装选全）或用户撤销。
    //
    // 批量快捷动作（全部还原 / 清空回收站）走的是**同一条**通道：它们同样要求
    // 「读到全部」才允许执行，否则就是拿一部分冒充全部。
    LaunchedEffect(selectAllPending, bulkAction, loadingMore, loadError, systemTrash.size, trashCount) {
        if (!selectAllPending) return@LaunchedEffect
        if (loadError) {
            // 读不全就什么都不做。宁可让用户重试，也不能对不完整的列表执行「清空」。
            selectAllPending = false
            bulkAction = null
            return@LaunchedEffect
        }
        if (systemTrash.size >= trashCount) {
            val all = systemTrash
            when (bulkAction) {
                BulkAction.RestoreAll -> {
                    selectedIds = emptySet()
                    onRestore(all)
                }

                // 清空是不可逆操作：交给同一个确认框，让用户看到准确张数与占用空间
                BulkAction.DeleteAll -> {
                    selectedIds = emptySet()
                    confirmDelete = all
                }

                null -> selectedIds = all.map { it.id }.toSet()
            }
            selectAllPending = false
            bulkAction = null
            return@LaunchedEffect
        }
        // 还没读完且当前空闲就继续补。放在这里而不是只点一次，是因为点「全选」时
        // 可能正有一页在加载，那一次 onLoadAll 会被挡掉 —— 不重试就永远补不齐。
        if (!loadingMore) onLoadAll()
    }

    /** 记下要做的批量动作，剩下的交给上面那条补页通道。 */
    fun startBulk(action: BulkAction) {
        if (trashCount == 0) return
        bulkAction = action
        selectAllPending = true
    }

    val onSelectAll: () -> Unit = {
        when {
            selectAllPending -> selectAllPending = false
            allSelected -> selectedIds = emptySet()
            allLoaded -> selectedIds = systemTrash.map { it.id }.toSet()
            else -> selectAllPending = true
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // -------------------------------------------------------- 标题
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_settings),
                        )
                    }
                    Text(
                        text = stringResource(R.string.trash_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (trashCount > 0) {
                        TextButton(onClick = onSelectAll) {
                            Text(
                                text = stringResource(
                                    if (allSelected) R.string.trash_deselect_all
                                    else R.string.trash_select_all,
                                ),
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.trash_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trash_restore_all)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.RestoreFromTrash, null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        startBulk(BulkAction.RestoreAll)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trash_empty_all)) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) },
                                    onClick = {
                                        menuOpen = false
                                        startBulk(BulkAction.DeleteAll)
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = if (systemTrash.isEmpty()) {
                        stringResource(R.string.trash_subtitle_empty)
                    } else {
                        stringResource(R.string.trash_subtitle, photosLabel(trashCount))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 批量动作在补页期间必须给反馈：回收站可能有几千张，
                // 用户点了「清空」之后界面几秒没动静，会以为按钮坏了并反复点。
                if (bulkAction != null && selectAllPending) {
                    Text(
                        text = stringResource(R.string.trash_bulk_preparing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (systemTrash.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    icon = Icons.Rounded.RestoreFromTrash,
                    title = stringResource(R.string.trash_empty_title),
                    description = stringResource(R.string.trash_empty_desc),
                    accent = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            items(items = systemTrash, key = { "y-${it.id}" }) { image ->
                // 每格只订阅「自己这一格」的选中值。划选每帧改的是 selectedIds 整体，
                // 让格子直读这个集合的话，所有可见格子会逐帧重组（3 列 × 十来行，
                // 60~120fps 下是低端机的滚动/划选杀手）。derivedStateOf 把失效收窄到
                // 选中值真正翻转的那几格，并且惰性求值 —— 不在屏上的格子不参与重算。
                val selected by remember(image.id) { derivedStateOf { image.id in selectedIds } }
                TrashTile(
                    image = image,
                    selected = selected,
                    onClick = { selectedIds = selectedIds.toggle(image.id) },
                    onBounds = { rect -> tileBounds[image.id] = rect },
                    onDisposed = { tileBounds.remove(image.id) },
                    onDragStart = {
                        // 起手那格决定整段划选是加选还是减选：已选中的一片再划过去 = 取消
                        paint = if (image.id in selectedIds) PaintMode.Deselect else PaintMode.Select
                    },
                    onDragMove = ::paintAt,
                    onDragEnd = { paint = null },
                )
            }
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                SystemTrashActions(
                    selectedCount = selectedCount,
                    onRestore = {
                        onRestore(selectedList())
                        selectedIds = emptySet()
                    },
                    onDelete = { confirmDelete = selectedList() },
                )
            }
        }

        // 滚到这里说明已经看到最后一张，取下一页
        if (hasMore) {
            item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) {
                LaunchedEffect(systemTrash.size) { onLoadMore() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loadingMore -> CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                        loadError -> OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.trash_load_more_retry))
                        }
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }

        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(96.dp))
        }
    }

    DeleteConfirmDialog(
        pending = confirmDelete,
        onDismiss = { confirmDelete = null },
        onConfirm = { target ->
            confirmDelete = null
            selectedIds = emptySet()
            onDelete(target)
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    pending: List<MediaImage>?,
    onDismiss: () -> Unit,
    onConfirm: (List<MediaImage>) -> Unit,
) {
    val target = pending ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteForever,
                contentDescription = null,
                tint = PhotoTidyTheme.trash,
            )
        },
        title = { Text(stringResource(R.string.trash_delete_title, target.size)) },
        text = {
            Text(
                stringResource(
                    R.string.trash_delete_body,
                    target.map { it.sizeBytes }.sumReadableSize(),
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(target) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.action_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_reconsider)) }
        },
    )
}

@Composable
private fun TrashTile(
    image: MediaImage,
    selected: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
    onDisposed: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 本格在根坐标系里的左上角，用来把拖动坐标换算到与 tileBounds 同一坐标系。
    // neverEqualPolicy：位置每次滚动都在变，但这个值不该触发重组。
    val origin = remember { mutableStateOf(Offset.Zero, neverEqualPolicy()) }

    DisposableEffect(image.id) { onDispose(onDisposed) }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    origin.value = coords.positionInRoot()
                    onBounds(coords.boundsInRoot())
                }
                // 长按之后才进入拖动：短按仍是一次普通点击，垂直滑动仍然是滚动列表。
                // 拖动一进来就 consume，滚动手势便不会再把它当成滚动（子节点先于滚动容器拿到事件）。
                .pointerInput(image.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ ->
                            change.consume()
                            onDragMove(origin.value + change.position)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
        ) {
            LibraryImage(
                image = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                )
            }
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Black.copy(alpha = 0.35f)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp),
            ) {
                if (selected) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 划选模式。由起手那一格决定，整段拖动沿用同一种。 */
private enum class PaintMode { Select, Deselect }

/** 回收站的批量快捷动作。两个都要求「列表已读全」才允许执行。 */
private enum class BulkAction { RestoreAll, DeleteAll }

@Composable
private fun SystemTrashActions(
    selectedCount: Int,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = selectedCount > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (enabled) {
                selectedLabel(selectedCount)
            } else {
                stringResource(R.string.trash_select_hint)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRestore,
                enabled = enabled,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestoreFromTrash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_restore), fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onDelete,
                enabled = enabled,
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_delete_forever), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id
