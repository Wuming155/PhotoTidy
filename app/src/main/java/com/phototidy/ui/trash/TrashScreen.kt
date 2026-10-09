package com.phototidy.ui.trash

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
 */
@Composable
fun TrashScreen(
    systemTrash: List<MediaImage>,
    trashCount: Int,
    onRestore: (List<MediaImage>) -> Unit,
    onDelete: (List<MediaImage>) -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean = false,
    loadError: Boolean = false,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf<List<MediaImage>?>(null) }

    val selection = systemTrash.filter { it.id in selectedIds }
    val hasMore = systemTrash.size < trashCount

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
                modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
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
                    )
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
                TrashTile(
                    image = image,
                    selected = image.id in selectedIds,
                    onClick = { selectedIds = selectedIds.toggle(image.id) },
                )
            }
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                SystemTrashActions(
                    selectedCount = selection.size,
                    onRestore = {
                        onRestore(selection)
                        selectedIds = emptySet()
                    },
                    onDelete = { confirmDelete = selection },
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
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box {
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
