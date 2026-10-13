package com.phototidy.ui.duplicates

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.core.media.DuplicateGroup
import com.phototidy.core.model.MediaImage
import com.phototidy.ui.components.EmptyState
import com.phototidy.ui.components.LibraryImage
import com.phototidy.ui.components.selectedLabel
import com.phototidy.util.sumReadableSize
import com.phototidy.util.toReadableSize

/**
 * 重复照片页。
 *
 * 两件事刻意与参考实现不同（见 notes §5）：
 *  1) **纯本地**。参考实现的这一项带「高级」标、跑在什么基础设施上无法静态判定；
 *     这里不上传、不联网、不收费 —— 应用清单里连网络权限都没有。
 *  2) **入口不做开机预热**。比对要解码几百张缩略图，放在冷启动路径上等于让所有人
 *     先等几秒；改成进这一页才扫（[LaunchedEffect] 触发）。
 *
 * 交互上默认「每组保留第一张，其余勾上」：绝大多数人的意图就是留一张。
 * 但**不因此省略确认框** —— 出口是回收站，多一次确认换心理安全边际完全值得。
 */
@Composable
fun DuplicatesScreen(
    groups: List<DuplicateGroup>,
    scanning: Boolean,
    scanned: Boolean,
    onScan: () -> Unit,
    onTrash: (List<MediaImage>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    /**
     * 进页面就比对一次 —— 但**只在还没比对过的时候**。
     *
     * 「不做开机预热、进页面才扫」这条设计没有变（那条扫描要解码最多 800 张缩略图）；
     * 变的是「每次进页面」：结果还在内存里的时候重扫一遍，用户要多等几秒、
     * 还会先看到一次扫描中的空窗。要刷新结果有顶栏的「重新比对」按钮。
     */
    LaunchedEffect(Unit) { if (!scanned) onScan() }

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmTrash by remember { mutableStateOf<List<MediaImage>?>(null) }

    // 默认选中「每组除第一张以外的全部」。列表被替换（重新比对、删除后收敛）时重算一次，
    // 否则会留着已经不在结果里的幽灵 id，导致「选中 N 张」和实际能删的对不上。
    LaunchedEffect(groups) {
        selectedIds = groups
            .flatMap { group -> group.images.drop(1).map { it.id } }
            .toSet()
    }

    val selectedImages = groups
        .flatMap { it.images }
        .filter { it.id in selectedIds }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                    Text(
                        text = stringResource(R.string.duplicates_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (scanned && !scanning) {
                        TextButton(onClick = onScan) {
                            Text(stringResource(R.string.duplicates_rescan))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.duplicates_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            scanning -> item(key = "scanning") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.duplicates_scanning),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.duplicates_scanning_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            scanned && groups.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(R.string.duplicates_empty_title),
                    description = stringResource(R.string.duplicates_empty_desc),
                    accent = MaterialTheme.colorScheme.tertiary,
                )
            }

            else -> {
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.duplicates_select_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(items = groups, key = { "group-${it.images.first().id}" }) { group ->
                    DuplicateGroupCard(
                        group = group,
                        selectedIds = selectedIds,
                        onToggle = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                    )
                }
                item(key = "actions") {
                    DuplicateActions(
                        selectedCount = selectedImages.size,
                        onTrash = { confirmTrash = selectedImages },
                    )
                }
            }
        }

        item(key = "footer") { Spacer(Modifier.height(96.dp)) }
    }

    val pending = confirmTrash
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmTrash = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.RestoreFromTrash,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            title = { Text(stringResource(R.string.duplicates_confirm_title, pending.size)) },
            text = {
                Text(
                    stringResource(
                        R.string.duplicates_confirm_body,
                        pending.map { it.sizeBytes }.sumReadableSize(),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmTrash = null
                        onTrash(pending)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = null }) {
                    Text(stringResource(R.string.action_reconsider))
                }
            },
        )
    }
}

/**
 * 一组重复照片。
 *
 * 用「按 3 列切行」而不是嵌套一个 `LazyVerticalGrid`：外层已经是 LazyColumn，
 * 里层再来一个可滚动容器会当场抛「无限高度约束」异常，而这在真实设备上
 * 表现为进页面直接闪退。
 */
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.duplicates_group_header, group.images.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        R.string.duplicates_group_wasted,
                        group.wastedBytes.toReadableSize(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))

            group.images.chunked(3).forEach { rowImages ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowImages.forEach { image ->
                        DuplicateTile(
                            image = image,
                            selected = image.id in selectedIds,
                            onClick = { onToggle(image.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行不足 3 张时补空位，否则那几张会被拉伸成整行宽
                    repeat(3 - rowImages.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DuplicateTile(
    image: MediaImage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                )
            }
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
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
private fun DuplicateActions(selectedCount: Int, onTrash: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = selectedLabel(selectedCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onTrash,
            enabled = selectedCount > 0,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.RestoreFromTrash,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.duplicates_action, selectedCount),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
