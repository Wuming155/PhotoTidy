package com.phototidy.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.ReviewAction
import com.phototidy.ui.theme.PhotoTidyMotion
import com.phototidy.ui.theme.PhotoTidyTheme
import com.phototidy.util.grouped
import com.phototidy.util.toPhotoDateTime

/** 顶栏下拉里的一项。 */
data class ScopeOption(val label: String, val scope: MediaScope, val count: Int)

/**
 * 整理页。
 *
 * 版面自上而下严格按「信息 → 内容 → 动作」分层：
 *   1) 顶栏：我现在整理的是哪一堆（可切换）+ 出口；
 *   2) 进度：还剩多少，避免「无止境」的疲劳感；
 *   3) 卡片：唯一的视觉焦点，占屏幕一半以上；
 *   4) 动作条：四个手势的等价按钮，全部落在拇指区；
 *   5) 移动条：把照片「投递」到具体相册。
 *
 * 一个刻意的取舍：**页面里没有任何滚动容器**。
 * 整理是个高频重复动作，任何需要「先滑一下再操作」的结构都会破坏节奏。
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    scopeLabel: String,
    scopeOptions: List<ScopeOption>,
    sensitivity: Float,
    hapticsEnabled: Boolean,
    showNextPreview: Boolean,
    onClose: () -> Unit,
    onKeep: () -> Unit,
    onTrash: () -> Unit,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    onMove: (MediaAlbum) -> Unit,
    onOpenHelp: () -> Unit,
    onPickScope: (MediaScope) -> Unit,
    onRestart: () -> Unit,
    onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scopeMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var commitDialogVisible by remember { mutableStateOf(false) }

    // 「移动到相册」需要驱动卡片做一个下滑飞出动画，用 ticket 触发避免重复响应
    var moveTicket by remember { mutableLongStateOf(0L) }
    var moveTarget by remember { mutableStateOf<MediaAlbum?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 背景先铺满整屏（含系统栏区域），内容再避开安全区 —— 这样状态栏下面的
            // 背景色是连续的，而不是在状态栏处切一刀
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ---------------------------------------------------------- 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_exit_review))
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilledTonalButton(
                    onClick = { scopeMenuOpen = true },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        text = scopeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = stringResource(R.string.cd_switch_scope),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = scopeMenuOpen,
                    onDismissRequest = { scopeMenuOpen = false },
                ) {
                    scopeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.label,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = option.count.grouped(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                scopeMenuOpen = false
                                onPickScope(option.scope)
                            },
                        )
                    }
                }
            }

            // 提交：整个应用唯一的写入口。徽标 = 已排队、还没落到系统相册的改动数
            IconButton(
                onClick = { commitDialogVisible = true },
                enabled = state.hasPending && !state.submitting,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    BadgedBox(
                        badge = {
                            if (state.pendingTotal > 0) {
                                Badge { Text(state.pendingTotal.toString()) }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = stringResource(R.string.cd_submit))
                    }
                }
            }

            Box {
                IconButton(onClick = { moreMenuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.cd_more))
                }
                DropdownMenu(
                    expanded = moreMenuOpen,
                    onDismissRequest = { moreMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_help)) },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                        onClick = { moreMenuOpen = false; onOpenHelp() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_defer)) },
                        leadingIcon = { Icon(Icons.Rounded.SkipNext, null) },
                        onClick = { moreMenuOpen = false; onDefer() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_restart)) },
                        leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                        onClick = { moreMenuOpen = false; onRestart() },
                    )
                }
            }
        }

        // ---------------------------------------------------------- 进度
        ReviewProgress(state)

        // ---------------------------------------------------------- 卡片
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            if (state.preparing) {
                // 队列窗口见底、下一页还在路上。显示转圈好过显示一张空卡片 ——
                // 空卡片会被误读成「整理完了」。
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                AnimatedContent(
                    targetState = state.finished,
                    transitionSpec = {
                        fadeIn(PhotoTidyMotion.effectsDefault()) togetherWith
                            fadeOut(PhotoTidyMotion.effectsFast())
                    },
                    label = "review-content",
                ) { finished ->
                    if (finished) {
                        FinishedCard(
                            state = state,
                            onRestart = onRestart,
                            onRequestCommit = { commitDialogVisible = true },
                            onClose = onClose,
                        )
                    } else {
                        SwipeablePhotoCard(
                            image = state.current,
                            nextImage = state.next,
                            hapticsEnabled = hapticsEnabled,
                            sensitivity = sensitivity,
                            showNextPreview = showNextPreview,
                            moveTicket = moveTicket,
                            moveTarget = moveTarget,
                            onAction = { action, album ->
                                when (action) {
                                    ReviewAction.Keep -> onKeep()
                                    ReviewAction.Trash -> onTrash()
                                    ReviewAction.Move -> album?.let(onMove)
                                }
                                moveTarget = null
                            },
                            onUndo = onUndo,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ------------------------------------------- 手势提示 / 排队状态
        Text(
            text = stringResource(R.string.review_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Text(
            text = when {
                state.submitting -> state.submittingLabel
                state.hasPending -> stringResource(R.string.review_queued, pendingSummary(state))
                else -> stringResource(R.string.review_idle)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.hasPending) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp),
        )

        // ---------------------------------------------------------- 动作条
        ReviewActionBar(
            canUndo = state.canUndo,
            enabled = !state.finished && !state.preparing,
            onUndo = onUndo,
            onKeep = onKeep,
            onTrash = onTrash,
        )

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------- 快捷移动
        AlbumQuickMoveBar(
            albums = state.moveTargets,
            enabled = !state.finished && !state.preparing,
            onMove = { album ->
                moveTarget = album
                moveTicket = System.nanoTime()
            },
        )
    }

    // ---------------------------------------------------------- 提交确认
    // 这是用户在整个流程里唯一需要做决定的地方：之前滑过的每一张都还能撤销。
    if (commitDialogVisible) {
        AlertDialog(
            onDismissRequest = { commitDialogVisible = false },
            icon = { Icon(Icons.Rounded.DoneAll, contentDescription = null) },
            title = { Text(stringResource(R.string.commit_dialog_title)) },
            text = {
                Column {
                    val moveLine = if (state.pendingMoveCount > 0) {
                        stringResource(R.string.commit_dialog_move, state.pendingMoveCount)
                    } else {
                        null
                    }
                    val trashLine = if (state.stagedCount > 0) {
                        stringResource(R.string.commit_dialog_trash, state.stagedCount)
                    } else {
                        null
                    }
                    Text(text = listOfNotNull(moveLine, trashLine).joinToString("\n"))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.commit_dialog_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        commitDialogVisible = false
                        onCommit()
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(stringResource(R.string.action_confirm_commit))
                }
            },
            dismissButton = {
                TextButton(onClick = { commitDialogVisible = false }) {
                    Text(stringResource(R.string.action_think_again))
                }
            },
        )
    }
}

/** 「移动 3 张 + 回收 2 张」，用于状态行。 */
@Composable
private fun pendingSummary(state: ReviewUiState): String {
    val move = if (state.pendingMoveCount > 0) {
        stringResource(R.string.summary_move, state.pendingMoveCount)
    } else {
        null
    }
    val trash = if (state.stagedCount > 0) {
        stringResource(R.string.summary_trash, state.stagedCount)
    } else {
        null
    }
    return listOfNotNull(move, trash).joinToString(" + ")
}

@Composable
private fun ReviewProgress(state: ReviewUiState) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${(state.processed + 1).coerceAtMost(state.total)} / ${state.total.grouped()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.current?.takenAtMillis?.toPhotoDateTime().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewActionBar(
    canUndo: Boolean,
    enabled: Boolean,
    onUndo: () -> Unit,
    onKeep: () -> Unit,
    onTrash: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            icon = Icons.AutoMirrored.Rounded.Undo,
            label = stringResource(R.string.action_undo),
            tint = PhotoTidyTheme.undo,
            enabled = enabled && canUndo,
            onClick = onUndo,
        )
        // 保留是最高频的动作，给它更大的视觉重量和更大的命中区
        ActionButton(
            icon = Icons.Rounded.Check,
            label = stringResource(R.string.action_keep),
            tint = PhotoTidyTheme.keep,
            enabled = enabled,
            primary = true,
            onClick = onKeep,
        )
        ActionButton(
            icon = Icons.Rounded.DeleteOutline,
            label = stringResource(R.string.action_clear),
            tint = PhotoTidyTheme.trash,
            enabled = enabled,
            onClick = onTrash,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val diameter = if (primary) 62.dp else 52.dp
    val alpha = if (enabled) 1f else 0.35f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (primary) {
                tint.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.size(diameter),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint.copy(alpha = alpha),
                    modifier = Modifier.size(if (primary) 28.dp else 23.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

@Composable
private fun FinishedCard(
    state: ReviewUiState,
    onRestart: () -> Unit,
    onRequestCommit: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.finished_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.finished_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatCell(stringResource(R.string.stat_keep), state.keptCount, PhotoTidyTheme.keep)
                StatCell(
                    stringResource(R.string.stat_move),
                    state.movedCount,
                    MaterialTheme.colorScheme.tertiary,
                )
                StatCell(stringResource(R.string.stat_trash), state.trashedCount, PhotoTidyTheme.trash)
            }
            Spacer(Modifier.height(28.dp))
            if (state.hasPending) {
                FilledTonalButton(
                    onClick = onRequestCommit,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(stringResource(R.string.action_submit_pending, state.pendingTotal))
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClose, shape = MaterialTheme.shapes.extraLarge) {
                    Text(stringResource(R.string.action_back_home))
                }
                FilledTonalButton(onClick = onRestart, shape = MaterialTheme.shapes.extraLarge) {
                    Text(stringResource(R.string.action_restart))
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
