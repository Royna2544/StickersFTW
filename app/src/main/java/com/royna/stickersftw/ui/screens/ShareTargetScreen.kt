package com.royna.stickersftw.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.conversion.SizeBudget
import com.royna.stickersftw.model.PackStatus
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.PageHeader

/** Where shared media should go: into a new pack, or into one that has room.
 *
 * Shown when the app is opened from the system share sheet. A pack is only
 * offered if it is Ready and can hold everything that was shared -- offering
 * one that fits some of it would mean either silently dropping the rest or
 * failing after the user has already chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen(
    packs: List<StickerPack>,
    sharedCount: Int,
    enabled: Boolean = true,
    onCreateNew: () -> Unit,
    onAddToPack: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val candidates = packs.filter { pack ->
        pack.status == PackStatus.Ready &&
            SizeBudget.MAX_STICKERS - pack.stickerCount >= sharedCount
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_target_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 22.dp),
        ) {
            PageHeader(
                title = pluralStringResource(R.plurals.share_target_header, sharedCount, sharedCount),
                subtitle = stringResource(R.string.share_target_subtitle),
                modifier = Modifier.padding(top = 18.dp, bottom = 18.dp),
            )

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onCreateNew),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AddCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.share_target_new_pack),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.share_target_existing),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (candidates.isEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.share_target_none_fit,
                        sharedCount,
                        sharedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(candidates, key = { it.id }) { pack ->
                        val free = SizeBudget.MAX_STICKERS - pack.stickerCount
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { onAddToPack(pack.id) },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = pack.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.add_source_remaining,
                                        free,
                                        free,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
