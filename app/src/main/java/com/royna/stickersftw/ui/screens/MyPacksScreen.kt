package com.royna.stickersftw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.model.StickerPack
import com.royna.stickersftw.ui.components.PackListCard
import com.royna.stickersftw.ui.components.PageHeader

@Composable
fun MyPacksScreen(
    packs: List<StickerPack>,
    onOpenPack: (String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onDeletePack: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    var query by rememberSaveable { mutableStateOf("") }

    val filteredPacks = packs.filter { pack ->
        query.isBlank() ||
            pack.title.contains(query, ignoreCase = true) ||
            pack.author.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 22.dp),
    ) {
        PageHeader(
            title = "My Packs",
            subtitle = "${packs.size} sticker packs",
            modifier = Modifier.padding(top = 22.dp, bottom = 18.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search packs…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            // weight(1f), not fillMaxSize(): this Column isn't scrollable
            // itself, so an unweighted fillMaxSize() child would claim the
            // *entire* column height on top of the header/search already
            // stacked above it, overflowing past the screen and clipping
            // the last few packs instead of letting you scroll to them.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filteredPacks, key = { it.id }) { pack ->
                PackListCard(
                    pack = pack,
                    onClick = { onOpenPack(pack.id) },
                    onTogglePinned = { onTogglePinned(pack.id) },
                    onDelete = { onDeletePack(pack.id) },
                )
            }
        }
    }
}
