package com.phototidy.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phototidy.R
import com.phototidy.core.model.MediaAlbum
import com.phototidy.ui.components.photosLabel
import com.phototidy.ui.theme.BottomSheetShape

/**
 * 底部的「一键移动」条。
 *
 * 这是整个应用里最高频的第二个动作，所以它的位置和形态都很讲究：
 *  · 贴在最底部，拇指自然落点；
 *  · 每一个目标都是独立的 72dp 宽触控区，不会误触相邻项；
 *  · 用「下载箭头落到横线」的图标而不是缩略图 —— 这里是「动作」不是「浏览」，
 *    放缩略图反而会让用户以为点下去是打开相册。
 */
@Composable
fun AlbumQuickMoveBar(
    albums: List<MediaAlbum>,
    onMove: (MediaAlbum) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * 「新相册…」的入口。传 null 时不显示这一项 ——
     * 相册页等只做「选目标」的场景不该出现「新建」的旁路。
     */
    onNewAlbum: (() -> Unit)? = null,
) {
    Surface(
        shape = BottomSheetShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
            // 标签重名时补一行路径（见 [AlbumMoveTarget.showPath]）：
            // ROM 会把 DCIM/Camera 的桶名本地化成「相机」，设备上若真有个 DCIM/相机，
            // 两张卡就长得一模一样 —— 用户没法知道点下去是哪个目录。
            val collidingNames = remember(albums) {
                albums.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // key 用 id+name 的组合而不只 id：列表里混有「系统标准目录」的合成相册
                // 和本次会话新建的相册，它们的 id 都是 Int32 范围外的哨兵值
                // （见 LibraryViewModel.SYNTHETIC_BUCKET_BASE / ReviewViewModel.createAlbum）。
                // 让 key 同时携带名字，把唯一性由构造保证，而不是依赖「id 数值范围永不重叠」。
                items(items = albums, key = { "${it.bucketId}:${it.name}" }) { album ->
                    AlbumMoveTarget(
                        album = album,
                        enabled = enabled,
                        showPath = album.name in collidingNames,
                        onClick = { onMove(album) },
                    )
                }
                // 「新相册」永远排在最后：它是低频动作，不能挡在常用相册前面；
                // 但必须留在同一条横向列表里 —— 建完就要立刻投递当前这张，
                // 把入口放到别处（菜单、弹窗）会让「建相册」和「移动」变成两个割裂的动作。
                if (onNewAlbum != null) {
                    item(key = "__new_album__") {
                        NewAlbumTarget(enabled = enabled, onClick = onNewAlbum)
                    }
                }
            }
        }
    }
}

/**
 * 「新相册…」。视觉上刻意与真实相册区分：虚线感的中性色描边 + 「+」图标，
 * 而不是跟其他卡一样的实心容器 —— 它不是一个可以投递的目标，而是一个动作。
 */
@Composable
private fun NewAlbumTarget(enabled: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val newAlbumLabel = stringResource(R.string.album_new_tile)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(72.dp)
            .semantics { contentDescription = newAlbumLabel },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CreateNewFolder,
                    contentDescription = null,
                    tint = outline,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = newAlbumLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumMoveTarget(
    album: MediaAlbum,
    enabled: Boolean,
    /** 标签在本条列表里重名：补一行目录路径，否则两张卡完全一样，无法判断点的是哪个目录。 */
    showPath: Boolean,
    onClick: () -> Unit,
) {
    val folder = album.relativePath.trim('/')
    // 卡片是 72dp 见方，路径只能截断显示 —— 但开头就是区分点（DCIM/C… 与 DCIM/相…），
    // 够用了。无障碍则把完整路径读出来。
    val described = stringResource(R.string.cd_move_target_folder, album.name, folder)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(72.dp)
            .then(
                if (showPath) {
                    Modifier.semantics { contentDescription = described }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPath) {
                Text(
                    text = folder,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (album.count > 0) {
                Text(
                    text = photosLabel(album.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
