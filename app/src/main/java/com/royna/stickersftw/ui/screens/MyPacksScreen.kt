package com.royna.stickersftw.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.PackOrigin
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
    var activeFilters by rememberSaveable(stateSaver = PackFilterSetSaver) {
        mutableStateOf(emptySet<PackFilter>())
    }

    // Chips are offered for the properties this library actually has, so an
    // account with no created packs isn't shown a "Created" chip that can
    // only ever empty the list. An active filter keeps its chip regardless,
    // otherwise filtering the last matching pack away would hide the control
    // still doing the filtering.
    val availableFilters = PackFilter.entries.filter { filter ->
        filter in activeFilters || packs.any(filter.matches)
    }
    val filteredPacks = packs.filter { pack ->
        pack.matchesFilters(activeFilters) &&
            (
                query.isBlank() ||
                    pack.title.contains(query, ignoreCase = true) ||
                    pack.author.contains(query, ignoreCase = true)
                )
    }

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
        if (availableFilters.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableFilters, key = { it.name }) { filter ->
                    val selected = filter in activeFilters
                    FilterChip(
                        selected = selected,
                        onClick = {
                            activeFilters = if (selected) {
                                activeFilters - filter
                            } else {
                                activeFilters + filter
                            }
                        },
                        label = { Text(stringResource(filter.labelRes)) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                        // Material3 pairs a selected chip with secondaryContainer,
                        // which is Telegram blue's container here. The rest of
                        // the app's selected states are the primaryContainer
                        // lavender, so match that.
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                if (activeFilters.isNotEmpty()) {
                    item(key = "clear") {
                        AssistChip(
                            onClick = { activeFilters = emptySet() },
                            label = { Text(stringResource(R.string.pack_filters_clear)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.cd_clear_filters),
                                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                                )
                            },
                        )
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
                    // The Scaffold's FAB floats over this list and its inset
                    // is not part of the content padding handed down, so the
                    // bottom pad has to clear it by hand: 56dp button + 16dp
                    // Scaffold margin, plus room to breathe. Without it the
                    // last card's overflow menu sits under the FAB and cannot
                    // be tapped at all.
                    contentPadding = PaddingValues(top = 14.dp, bottom = 88.dp),
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
                            if (query.isBlank() && activeFilters.isEmpty()) {
                                R.string.my_packs_empty
                            } else {
                                R.string.my_packs_search_no_results
                            },
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

/** A single filter chip above the pack list.
 *
 * Chips sharing a [group] are alternatives and OR together; separate groups
 * AND. So Animated + Imported narrows to imported animated packs, while
 * Animated + Static widens back to everything -- which is what picking
 * "either type" should mean. Filters that stand alone (Pinned, Updates) each
 * get their own group, so a one-member OR leaves them as plain AND toggles
 * and the rule stays uniform. */
private enum class PackFilter(
    val group: Group,
    @StringRes val labelRes: Int,
    val matches: (StickerPack) -> Boolean,
) {
    Pinned(Group.Pinned, R.string.pack_filter_pinned, { it.isPinned }),
    UpdateAvailable(Group.Updates, R.string.pack_filter_update_available, { it.updateAvailable }),
    Animated(Group.Type, R.string.pack_filter_animated, { it.isAnimated }),
    Static(Group.Type, R.string.pack_filter_static, { !it.isAnimated }),
    Imported(Group.Origin, R.string.pack_filter_imported, { it.origin == PackOrigin.Imported }),
    Created(Group.Origin, R.string.pack_filter_created, { it.origin == PackOrigin.Created }),
    ;

    enum class Group { Pinned, Updates, Type, Origin }
}

private fun StickerPack.matchesFilters(active: Set<PackFilter>): Boolean =
    active.groupBy { it.group }.values.all { group -> group.any { it.matches(this) } }

/** Enums aren't bundle-storable, so the selection round-trips through
 * process death as its member names. Unknown names are dropped rather than
 * throwing, which keeps a saved state from an older build harmless. */
private val PackFilterSetSaver = listSaver<Set<PackFilter>, String>(
    save = { filters -> filters.map { it.name } },
    restore = { names ->
        names.mapNotNullTo(mutableSetOf()) { name ->
            PackFilter.entries.firstOrNull { it.name == name }
        }
    },
)
