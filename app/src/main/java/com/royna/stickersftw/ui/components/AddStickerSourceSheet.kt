package com.royna.stickersftw.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R

/** Where a new sticker can come from.
 *
 * An enum rather than a hardcoded row so that adding a source is adding an
 * entry: the sheet renders whatever is listed here. Only the device's own
 * media is available today -- pulling media from a URL is a separate problem
 * with its own answer, and listing it here before it works would promise
 * something the button cannot do. */
enum class AddStickerSource(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    DeviceMedia(
        labelRes = R.string.add_source_device_media,
        descriptionRes = R.string.add_source_device_media_body,
        icon = Icons.Rounded.PhotoLibrary,
    ),
}

/** Asks where the stickers should come from, and says how many will fit.
 *
 * The remaining count is on the sheet rather than only on the button behind
 * it, because it is the thing that decides how many the user should pick and
 * the picker itself gives no hint about it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStickerSourceSheet(
    remaining: Int,
    onPick: (AddStickerSource) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.add_source_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.add_source_remaining, remaining, remaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            for (source in AddStickerSource.entries) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(source) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        source.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(source.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(source.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
