package com.phototidy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phototidy.R
import com.phototidy.ui.theme.PhotoTidyTheme

/**
 * 使用方法。首次进入整理页自动弹出一次 —— 四个方向的手势不是自解释的，
 * 与其让用户瞎滑三张才发现，不如开场就讲清楚（而且只讲一次，之后不再打扰）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.help_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.help_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))

            GestureRow(
                icon = Icons.Rounded.West,
                tint = PhotoTidyTheme.keep,
                title = stringResource(R.string.help_keep_title),
                body = stringResource(R.string.help_keep_body),
            )
            GestureRow(
                icon = Icons.Rounded.DeleteOutline,
                tint = PhotoTidyTheme.trash,
                title = stringResource(R.string.help_trash_title),
                body = stringResource(R.string.help_trash_body),
            )
            GestureRow(
                icon = Icons.AutoMirrored.Rounded.Undo,
                tint = PhotoTidyTheme.undo,
                title = stringResource(R.string.help_undo_title),
                body = stringResource(R.string.help_undo_body),
            )
            GestureRow(
                icon = Icons.Rounded.South,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.help_move_title),
                body = stringResource(R.string.help_move_body),
            )

            Spacer(Modifier.height(18.dp))

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.help_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                )
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_got_it), modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun GestureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.16f)) {
            Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
