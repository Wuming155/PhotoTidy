package com.phototidy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phototidy.BuildConfig
import com.phototidy.R
import com.phototidy.core.settings.AppSettings
import com.phototidy.core.settings.ThemeMode
import com.phototidy.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    systemTrashCount: Int,
    onOpenTrash: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "topbar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    text = stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // -------------------------------------------------------- 回收站
        // 回收站从底部导航挪到这里：它是个低频的「善后」入口，
        // 不该和「整理 / 相册」这两个每天都在用的动作抢导航位。
        item(key = "trash-header") {
            SectionHeader(
                title = stringResource(R.string.settings_section_dest),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item(key = "trash-entry") {
            Surface(
                onClick = onOpenTrash,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.trash_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = trashSubtitle(systemTrashCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // -------------------------------------------------------- 外观
        item(key = "look-header") {
            SectionHeader(
                title = stringResource(R.string.settings_section_appearance),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item(key = "theme") {
            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_theme_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdate { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                        ) {
                            Text(
                                text = when (mode) {
                                    ThemeMode.System -> stringResource(R.string.settings_theme_system)
                                    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                                    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "dynamic") {
            SwitchRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_dynamic_title),
                subtitle = stringResource(R.string.settings_dynamic_desc),
                checked = settings.dynamicColor,
                onCheckedChange = { value -> onUpdate { it.copy(dynamicColor = value) } },
            )
        }

        item(key = "pure-black") {
            SwitchRow(
                icon = Icons.Rounded.DarkMode,
                title = stringResource(R.string.settings_pure_black_title),
                subtitle = stringResource(R.string.settings_pure_black_desc),
                checked = settings.pureBlack,
                enabled = settings.themeMode != ThemeMode.Light,
                onCheckedChange = { value -> onUpdate { it.copy(pureBlack = value) } },
            )
        }

        // -------------------------------------------------------- 整理
        item(key = "review-header") {
            SectionHeader(
                title = stringResource(R.string.settings_section_feel),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item(key = "sensitivity") {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_sensitivity_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            settings.swipeSensitivity < 0.85f ->
                                stringResource(R.string.settings_sensitivity_low)

                            settings.swipeSensitivity > 1.25f ->
                                stringResource(R.string.settings_sensitivity_high)

                            else -> stringResource(R.string.settings_sensitivity_normal)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_sensitivity_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.swipeSensitivity,
                    onValueChange = { value -> onUpdate { it.copy(swipeSensitivity = value) } },
                    valueRange = 0.6f..1.8f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "haptics") {
            SwitchRow(
                icon = Icons.Rounded.Vibration,
                title = stringResource(R.string.settings_haptics_title),
                subtitle = stringResource(R.string.settings_haptics_desc),
                checked = settings.hapticsEnabled,
                onCheckedChange = { value -> onUpdate { it.copy(hapticsEnabled = value) } },
            )
        }

        item(key = "preview") {
            SwitchRow(
                icon = Icons.Rounded.PhotoSizeSelectLarge,
                title = stringResource(R.string.settings_preview_title),
                subtitle = stringResource(R.string.settings_preview_desc),
                checked = settings.showNextPreview,
                onCheckedChange = { value -> onUpdate { it.copy(showNextPreview = value) } },
            )
        }

        // -------------------------------------------------------- 数据
        item(key = "data-header") {
            SectionHeader(
                title = stringResource(R.string.settings_section_data),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        item(key = "empty-albums") {
            SwitchRow(
                icon = Icons.Rounded.PhotoSizeSelectLarge,
                title = stringResource(R.string.settings_empty_albums_title),
                subtitle = stringResource(R.string.settings_empty_albums_desc),
                checked = settings.showEmptyAlbums,
                onCheckedChange = { value -> onUpdate { it.copy(showEmptyAlbums = value) } },
            )
        }

        item(key = "privacy") {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(10.dp))
                PrivacyLine(Icons.Rounded.CloudOff, stringResource(R.string.settings_privacy_no_network))
                PrivacyLine(Icons.Rounded.Shield, stringResource(R.string.settings_privacy_only_images))
                PrivacyLine(Icons.Rounded.Lock, stringResource(R.string.settings_privacy_confirm))
            }
        }

        item(key = "about") {
            SettingsCard {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "footer") { Spacer(Modifier.height(96.dp)) }
    }
}

/** 回收站入口的副标题：系统回收站里现在有多少张。 */
@Composable
private fun trashSubtitle(system: Int): String = if (system == 0) {
    stringResource(R.string.settings_trash_subtitle_empty)
} else {
    stringResource(R.string.settings_trash_subtitle, system)
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PrivacyLine(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
