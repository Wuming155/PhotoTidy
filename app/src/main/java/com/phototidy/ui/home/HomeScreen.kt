package com.phototidy.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.phototidy.R
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.MonthGroup
import com.phototidy.ui.LibraryUiState
import com.phototidy.ui.components.AlbumRow
import com.phototidy.ui.components.EmptyState
import com.phototidy.ui.components.LibraryImage
import com.phototidy.ui.components.SectionHeader
import com.phototidy.ui.components.TinyBadge
import com.phototidy.ui.components.photosLabel
import com.phototidy.ui.components.remainingCompactLabel
import com.phototidy.ui.components.remainingLabel
import com.phototidy.ui.theme.PhotoCardShape
import com.phototidy.util.toReadableSize

/**
 * 首页 / 时间线。
 *
 * 版面顺序遵循一个反直觉但正确的原则：**信息在上，动作在下**。
 * 顶部回答「今天有多少东西值得整理」，越靠近拇指的位置才是真正的操作入口。
 * 唯一例外是封面卡片 —— 它本身就是全屏最大的动作入口，所以给了它最大的面积。
 */
@Composable
fun HomeScreen(
    state: LibraryUiState,
    onOpenScope: (MediaScope) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val sizeLabel = state.totalBytes.toReadableSize()
    // 主数字是「还剩多少要整理」。全都整理完了才退回去报总数，否则屏幕上会出现
    // 一个恒为 0 的数字，看起来像照片丢了。
    val countLabel = if (state.remainingTotal > 0) {
        remainingLabel(state.remainingTotal)
    } else {
        photosLabel(state.totalCount)
    }

    // 截图磁贴上的数字。必须在 `LazyColumn` 的**外面**算：`LazyListScope` 的
    // content lambda 不是组合上下文，把 `stringResource` 之类放进去直接编译不过。
    val screenshotBadge = if (state.screenshotCount > 0) {
        remainingCompactLabel(state.screenshotRemaining, state.screenshotCount)
    } else {
        null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------------------------------------------------- 标题区
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tab_review),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when {
                            state.totalCount == 0 -> stringResource(R.string.home_subtitle_empty)
                            state.remainingTotal == 0 ->
                                stringResource(R.string.home_subtitle_done, sizeLabel)

                            else -> stringResource(
                                R.string.home_subtitle_remaining,
                                remainingLabel(state.remainingTotal),
                                sizeLabel,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isPartial) {
                    TinyBadge(
                        text = stringResource(R.string.badge_partial),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_rescan))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.tab_settings))
                }
            }
        }

        // ---------------------------------------------------------- 封面卡片
        item(key = "hero") {
            HeroCard(
                cover = state.newest,
                countLabel = countLabel,
                // 徽标只在真的「整理过一些」时才说「继续整理」——以前它是写死的，
                // 全新安装也会显示「继续整理」，与首屏的事实不符。
                badgeLabel = stringResource(
                    if (state.reviewedCount > 0) R.string.home_hero_badge else R.string.home_hero_action,
                ),
                onClick = { onOpenScope(MediaScope.Recent) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // ---------------------------------------------------------- 快捷分类
        // 分类入口按「有内容才出现」处理：「截图 · 剩 0 / 0」这种空磁贴对用户是纯噪音。
        // 「重复照片」是唯一例外 —— 有没有重复必须扫过才知道，而这条扫描要解码几百张
        // 缩略图，不能塞进冷启动。所以它常驻，代价推给点进去的那一刻。
        val quickTiles = buildList {
            if (screenshotBadge != null) {
                add(
                    QuickTileSpec(
                        key = MediaScope.Screenshots.key,
                        titleRes = R.string.home_tile_screenshots,
                        subtitleRes = R.string.home_tile_screenshots_desc,
                        icon = Icons.Rounded.CropFree,
                        badge = screenshotBadge,
                        onClick = { onOpenScope(MediaScope.Screenshots) },
                    ),
                )
            }
            if (state.totalCount > 0) {
                add(
                    QuickTileSpec(
                        key = "duplicates",
                        titleRes = R.string.home_tile_duplicates,
                        subtitleRes = R.string.home_tile_duplicates_desc,
                        icon = Icons.Rounded.ContentCopy,
                        badge = null,
                        onClick = onOpenDuplicates,
                    ),
                )
            }
        }
        if (quickTiles.isNotEmpty()) {
            item(key = "quick-header") {
                SectionHeader(
                    title = stringResource(R.string.home_section_quick),
                    subtitle = stringResource(R.string.home_section_quick_sub),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                )
            }
            item(key = "quick-tiles") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = quickTiles, key = { it.key }) { tile ->
                        QuickTile(spec = tile)
                    }
                }
            }
        }

        // ---------------------------------------------------------- 按月
        if (state.months.isNotEmpty()) {
            item(key = "months-header") {
                SectionHeader(
                    title = stringResource(R.string.home_section_time),
                    subtitle = stringResource(R.string.month_count, state.months.size),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                )
            }
            item(key = "months") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = state.months, key = { "${it.year}-${it.month}" }) { month ->
                        MonthCard(
                            month = month,
                            onClick = {
                                onOpenScope(MediaScope.Month(month.year, month.month))
                            },
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- 文件夹
        // 相册全部来自 `loadOverview` 的聚合，每一项都至少有一张照片 ——
        // MediaStore 里没有「空目录」这种实体，所以这里不存在「要不要显示 0 张的相册」这个问题。
        val folders = state.albums
        if (folders.isNotEmpty()) {
            item(key = "folders-header") {
                SectionHeader(
                    title = stringResource(R.string.home_section_folders),
                    subtitle = stringResource(R.string.home_section_folders_sub),
                    trailing = {
                        TextButton(onClick = onOpenAlbums) { Text(stringResource(R.string.action_all)) }
                    },
                    modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp),
                )
            }
            // bucketId 在这里天然唯一：列表来自 loadOverview 的 HashMap 聚合，
            // 不含「系统标准目录」的合成相册，不需要快捷移动条那样的组合 key
            items(items = folders.take(6), key = { it.bucketId }) { album ->
                AlbumRow(
                    album = album,
                    onClick = { onOpenScope(MediaScope.Album(album.bucketId, album.name)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ---------------------------------------------------------- 空态
        if (state.totalCount == 0 && !state.loading) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.home_empty_title),
                    description = if (state.canRead) {
                        stringResource(R.string.home_empty_desc_read)
                    } else {
                        stringResource(R.string.home_empty_desc_denied)
                    },
                    action = {
                        TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_rescan)) }
                    },
                )
            }
        }

        // 给底部的导航栏和 FAB 留出空间
        item(key = "footer") { Spacer(Modifier.height(104.dp)) }
    }
}

/**
 * 封面卡片：用两层错位的浅色卡片模拟「一叠照片」的厚度。
 * 这两层不是装饰 —— 它把「还有非常多张」这件事具象化了，比一个数字更有说服力。
 */
@Composable
private fun HeroCard(
    cover: MediaImage?,
    countLabel: String,
    badgeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(318.dp),
    ) {
        Surface(
            shape = PhotoCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.80f)
                .height(300.dp)
                .offset(y = 16.dp)
                .zIndex(0f),
        ) {}

        Surface(
            shape = PhotoCardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.90f)
                .height(300.dp)
                .offset(y = 8.dp)
                .zIndex(1f),
        ) {}

        Surface(
            onClick = onClick,
            shape = PhotoCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(300.dp)
                .zIndex(2f),
        ) {
            Box {
                LibraryImage(
                    image = cover,
                    contentDescription = stringResource(R.string.cd_recent_photo),
                    contentScale = ContentScale.Crop,
                    // 首页封面是整屏最大的一张图，首帧加载出来时值得一次淡入
                    crossfade = true,
                    modifier = Modifier.fillMaxSize(),
                )
                // 底部渐变：保证白色文字在任何照片上都可读
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.42f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.74f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                ) {
                    TinyBadge(
                        text = badgeLabel,
                        container = Color.White.copy(alpha = 0.22f),
                        content = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = countLabel,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.home_hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/**
 * 快捷分类磁贴的数据。
 *
 * 只带「这个范围叫什么、还剩多少」—— 点进去之后走的是同一个整理会话机制，
 * 不需要任何分类专属的代码路径（见 notes §4：参考实现也没有独立的截图页面）。
 *
 * 「重复照片」这类**需要先扫描才知道结果**的入口 [badge] 传 null：
 * 在详情页里现扫现报，而不是逼每个用户在冷启动时先等一次全库比对。
 */
private data class QuickTileSpec(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
    /** 副标题下面的数字行（例如「剩 3 / 12」）；null = 这一项没有预先知道的数字。 */
    val badge: String?,
    val onClick: () -> Unit,
)

/**
 * 分类磁贴：图标 + 名字 + 可选的「剩 N / M」。
 *
 * 数字口径与全应用一致：主数字是「还剩多少要整理」，总数降为副信息。
 * 没有预先知道的数字（重复照片）时不占位，直接不渲染那一行。
 */
@Composable
private fun QuickTile(spec: QuickTileSpec) {
    Surface(
        onClick = spec.onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .width(188.dp)
            .height(84.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(spec.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = spec.badge ?: stringResource(spec.subtitleRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (spec.badge != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spec.badge != null) {
                    Text(
                        text = stringResource(spec.subtitleRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCard(month: MonthGroup, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .width(124.dp)
            .height(160.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(MaterialTheme.shapes.large),
            ) {
                LibraryImage(
                    image = month.cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    text = stringResource(R.string.month_short, month.month),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 瓦片很窄，用紧凑写法；但口径保持一致 —— 一律「剩 N / M」，
                // 绝不在这里退回「N 张」这种看不出口径的写法。
                Text(
                    text = remainingCompactLabel(month.remaining, month.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (month.remaining == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
