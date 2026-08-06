package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.PackListCard
import com.royna.stickersftw.ui.components.PageHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPacksScreen(
    packs: List<StickerPack>,
    onOpenPack: (String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onDeletePack: (String) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRequestUpdate: (String) -> Unit,
    onDisableUpdates: (String) -> Unit,
    contentPadding: PaddingValues,
    activeConversion: ConversionUiState = ConversionUiState(),
    onResumeConversion: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // true = animated-only, false = static-only, null = no type filter --
    // picked from the search field's suggestions rather than typed text,
    // since "animated"/"static" aren't part of any pack's title/author.
    var typeFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val searchInteractionSource = remember { MutableInteractionSource() }
    val isSearchFocused by searchInteractionSource.collectIsFocusedAsState()

    val animatedCount = packs.count { it.isAnimated }
    val staticCount = packs.size - animatedCount
    val filteredPacks = packs.filter { pack ->
        (typeFilter == null || pack.isAnimated == typeFilter) &&
            (
                query.isBlank() ||
                    pack.title.contains(query, ignoreCase = true) ||
                    pack.author.contains(query, ignoreCase = true)
                )
    }
    val showSuggestions = isSearchFocused && query.isBlank() && typeFilter == null &&
        (animatedCount > 0 || staticCount > 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 22.dp),
    ) {
        PageHeader(
            title = stringResource(R.string.my_packs_title),
            modifier = Modifier.padding(top = 22.dp, bottom = 18.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            interactionSource = searchInteractionSource,
            placeholder = { Text(stringResource(R.string.search_packs_placeholder)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        if (showSuggestions) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column {
                    if (animatedCount > 0) {
                        SearchSuggestionRow(
                            icon = Icons.Rounded.Movie,
                            label = stringResource(R.string.search_suggestion_animated_packs, animatedCount),
                            onClick = { typeFilter = true },
                        )
                    }
                    if (staticCount > 0) {
                        SearchSuggestionRow(
                            icon = Icons.Rounded.Photo,
                            label = stringResource(R.string.search_suggestion_static_packs, staticCount),
                            onClick = { typeFilter = false },
                        )
                    }
                }
            }
        }
        if (typeFilter != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(100.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (typeFilter == true) R.string.search_suggestion_animated_packs_label else R.string.search_suggestion_static_packs_label,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { typeFilter = null }, modifier = Modifier.height(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_filter))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            // weight(1f), not fillMaxSize(): this Column isn't scrollable
            // itself, so an unweighted fillMaxSize() child would claim the
            // *entire* column height on top of the header/search already
            // stacked above it, overflowing past the screen and clipping
            // the last few packs instead of letting you scroll to them.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filteredPacks, key = { it.id }) { pack ->
                        val isActive = activeConversion.isRunning && activeConversion.packId == pack.id
                        PackListCard(
                            pack = pack,
                            onClick = { if (isActive) onResumeConversion(pack.id) else onOpenPack(pack.id) },
                            onTogglePinned = { onTogglePinned(pack.id) },
                            onDelete = { onDeletePack(pack.id) },
                            onRequestUpdate = { onRequestUpdate(pack.id) },
                            onDisableUpdates = { onDisableUpdates(pack.id) },
                            activeProgress = if (isActive) activeConversion.progress else null,
                        )
                    }
                }
                if (filteredPacks.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (query.isBlank() && typeFilter == null) R.string.my_packs_empty else R.string.my_packs_search_no_results,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
