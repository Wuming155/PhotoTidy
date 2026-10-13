package com.phototidy.ui.review

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.core.media.AlbumNameIssue
import com.phototidy.core.media.newAlbumIssue
import com.phototidy.core.model.MediaImage
import com.phototidy.ui.components.LibraryImage

/**
 * 退出整理的确认框。
 *
 * 三个动作，语义分清（参考实现只有「丢 / 不丢」两个，容易让用户误点掉一整轮整理）：
 *  · 继续整理 —— 纯关闭，什么都不动；
 *  · 保留退出 —— 保住排队中的改动，下次进来接着办（这是 PhotoTidy 本来就有的行为，
 *    比参考实现安全：它的排队改动只活在会话里）；
 *  · 丢弃改动 —— 回滚本次会话的全部决定。
 *
 * 数字直接用 `pendingTotal`（队列派生），UI 不自己数 —— 与完成页、提交确认页同源，
 * 三个页面的数字因此不可能对不上。
 */
@Composable
internal fun ExitReviewDialog(
    pendingCount: Int,
    onContinue: () -> Unit,
    onKeepAndLeave: () -> Unit,
    onDiscardAndLeave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.exit_review_title)) },
        text = { Text(stringResource(R.string.exit_review_desc, pendingCount)) },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onKeepAndLeave) {
                    Text(stringResource(R.string.exit_review_keep))
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = onDiscardAndLeave,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.exit_review_discard, pendingCount))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.exit_review_continue)) }
        },
    )
}

/**
 * 新建相册弹窗。
 *
 * 校验**在客户端先做**（重名 / 空名 / 非法字符），不等系统报错 ——
 * 把重名丢给 MediaStore 去拒绝，用户拿到的是一句底层异常，而且反馈比输入慢半拍。
 * 校验规则本身抽在 `core/media` 的 [newAlbumIssue] 里，能在 JVM 单测里直测。
 *
 * 这里刻意没有「将在你的画廊中创建相册」那个复选框：参考实现用它来选目标卷
 * （应用私有文件夹 / 安卓画廊），而 PhotoTidy 只有 MediaStore 一种存储 ——
 * 一个只有唯一选项的开关是纯噪音。
 */
@Composable
internal fun NewAlbumDialog(
    /** 当前会话已有的移动目标名，用于重名校验。 */
    existingNames: List<String>,
    onDismiss: () -> Unit,
    /** 返回 true = 创建成功；调用方据此关闭弹窗并把当前这张投递进新相册。 */
    onCreate: (String) -> Boolean,
) {
    var name by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    fun submit() {
        when (newAlbumIssue(name, existingNames)) {
            null -> if (!onCreate(name.trim())) {
                // 兜底：VM 侧的守卫比这里更严，能走到这说明会话内刚出现了同名目标
                errorRes = R.string.album_new_error_duplicate
            }

            AlbumNameIssue.Empty -> errorRes = R.string.album_new_error_empty
            AlbumNameIssue.IllegalChars -> errorRes = R.string.album_new_error_invalid
            AlbumNameIssue.Duplicate -> errorRes = R.string.album_new_error_duplicate
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
        title = { Text(stringResource(R.string.album_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        // 一开始打字就把上一次的错误抹掉：错误信息留在屏幕上会被当成
                        // 「现在的输入还是错的」，哪怕用户已经改了
                        errorRes = null
                    },
                    singleLine = true,
                    isError = errorRes != null,
                    label = { Text(stringResource(R.string.album_new_hint)) },
                    supportingText = errorRes?.let { res -> { Text(stringResource(res)) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.album_new_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { submit() }, shape = MaterialTheme.shapes.extraLarge) {
                Text(stringResource(R.string.album_new_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_think_again)) }
        },
    )
}

/**
 * 整理中的「回收站」—— 已排队待清除的照片。
 *
 * 它们**只在内存里**（`StagingTrash`），还没有落到系统回收站，所以这一页的语义是
 * 「反悔窗口」而不是「垃圾桶」：点任意一张就把它放回整理队列。
 *
 * 这解决了参考实现指出的那个真实痛点（见 notes §6b）：滑了几十张之后发现误删，
 * 在这之前只能一张张往下滑撤销 —— 而撤销栈是后进先出的，误删的那批如果不在栈顶，
 * 用户就得先撤销掉后来做的所有决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StagedTrashSheet(
    items: List<MediaImage>,
    onRestoreOne: (Long) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.staging_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.staging_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.staging_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                // 弹窗高度必须封顶：不封的话几百张暂存会让面板顶满全屏，
                // 底下那个「全部还原」按钮就永远推不到（用户得先滑到底）。
                modifier = Modifier.heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = items, key = { "staged-${it.id}" }) { image ->
                    StagedTile(image = image, onClick = { onRestoreOne(image.id) })
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRestoreAll,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestoreFromTrash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.staging_restore_all))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StagedTile(image: MediaImage, onClick: () -> Unit) {
    val label = stringResource(R.string.staging_restore_one)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LibraryImage(
                image = image,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small),
            )
            // 右下角的「还原」角标：没有它，用户会以为点下去是放大预览
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.RestoreFromTrash,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}
