package com.phototidy.ui.albums

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaScope
import com.phototidy.ui.LibraryUiState
import com.phototidy.ui.components.EmptyState
import com.phototidy.ui.components.LibraryImage
import com.phototidy.ui.components.photosLabel
import com.phototidy.ui.theme.PhotoCardShape

/**
 * 相册页。
 *
 * 用自适应网格而不是固定两列：在折叠屏展开态、平板上会自动变成 3~5 列，
 * 不需要任何额外的断点判断 —— 这正是 targetSdk 36 强制「大屏必须可调整尺寸」的应对方式。
 *
 * 网格尺寸用 `minSize` 而不是 `count`：前者是「每列至少多宽」，是自适应布局的唯一正确写法。
 */
@Composable
fun AlbumsScreen(
    state: LibraryUiState,
    onOpenAlbum: (MediaScope.Album) -> Unit,
    onRequestMoreAccess: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val albums = state.albums.filter { state.showEmptyAlbums || it.count > 0 }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Text(
                    text = stringResource(R.string.tab_albums),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (albums.isEmpty()) {
                        stringResource(R.string.albums_subtitle_empty)
                    } else {
                        stringResource(R.string.albums_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isPartial) {
            item(key = "partial-banner", span = { GridItemSpan(maxLineSpan) }) {
                PartialAccessBanner(onRequestMoreAccess = onRequestMoreAccess)
            }
        }

        if (albums.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    icon = Icons.Rounded.Collections,
                    title = stringResource(R.string.albums_empty_title),
                    description = stringResource(R.string.albums_empty_desc),
                )
            }
        }

        items(items = albums, key = { it.bucketId }) { album ->
            AlbumTile(album = album, onClick = {
                onOpenAlbum(MediaScope.Album(album.bucketId, album.name))
            })
        }

        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun AlbumTile(album: MediaAlbum, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = PhotoCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(PhotoCardShape),
            ) {
                LibraryImage(
                    image = album.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.6f),
                            ),
                        ),
                )
                Text(
                    text = photosLabel(album.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album.relativePath.isNotBlank()) {
                    Text(
                        text = album.relativePath.trimEnd('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 部分授权提示条。
 *
 * 必须显式告知用户「你只授权了一部分」——
 * 否则用户会以为应用漏掉了照片，而不是自己（或者系统的权限弹窗）做了取舍。
 */
@Composable
private fun PartialAccessBanner(onRequestMoreAccess: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.partial_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.partial_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Button(onClick = onRequestMoreAccess, shape = MaterialTheme.shapes.extraLarge) {
                Text(stringResource(R.string.action_select_more))
            }
        }
    }
}
