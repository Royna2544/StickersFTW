package com.royna.stickersftw.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePackScreen(
    onBack: () -> Unit,
    onPublish: (
        items: List<PickedMediaItem>,
        title: String,
        shortName: String,
        pushToTelegram: Boolean,
        addToWhatsapp: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val mediaItems = remember { mutableStateListOf<PickedMediaItem>() }
    var title by rememberSaveable { mutableStateOf("") }
    var shortName by rememberSaveable { mutableStateOf("") }
    var pushToTelegram by rememberSaveable { mutableStateOf(true) }
    var addToWhatsapp by rememberSaveable { mutableStateOf(true) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { uris ->
        for (uri in uris) {
            val mimeType = context.contentResolver.getType(uri)
            val kind = if (mimeType?.startsWith("video/") == true) PickedMediaKind.Video else PickedMediaKind.Image
            mediaItems.add(PickedMediaItem(uri = uri.toString(), kind = kind))
        }
    }

    val shortNameValid = shortName.isNotBlank() &&
        shortName.first().isLetter() &&
        shortName.all { it.isLetterOrDigit() || it == '_' }
    val canPublish = mediaItems.size >= 3 && title.isNotBlank() && shortNameValid && (pushToTelegram || addToWhatsapp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Pack") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Pick photos or short video clips, tag each with an emoji, and publish as a new pack.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                Text("  Pick photos/video (${mediaItems.size} selected)")
            }

            if (mediaItems.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mediaItems.size) { index ->
                        val item = mediaItems[index]
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    if (item.kind == PickedMediaKind.Video) {
                                        Icon(
                                            Icons.Rounded.Movie,
                                            contentDescription = "Video",
                                            modifier = Modifier.size(40.dp).padding(8.dp),
                                        )
                                    } else {
                                        coil3.compose.AsyncImage(
                                            model = item.uri,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                OutlinedTextField(
                                    value = item.emoji,
                                    onValueChange = { mediaItems[index] = item.copy(emoji = it) },
                                    modifier = Modifier.width(90.dp),
                                    label = { Text("Emoji") },
                                    singleLine = true,
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { mediaItems.removeAt(index) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pack title") },
                singleLine = true,
            )
            OutlinedTextField(
                value = shortName,
                onValueChange = { shortName = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Short name (for Telegram)") },
                supportingText = {
                    Text(
                        if (shortName.isBlank() || shortNameValid) {
                            "Letters, digits and underscores; must start with a letter."
                        } else {
                            "Must start with a letter and contain only letters, digits and underscores."
                        },
                    )
                },
                singleLine = true,
            )

            Text("Publish to", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pushToTelegram, onCheckedChange = { pushToTelegram = it })
                Text("Telegram")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = addToWhatsapp, onCheckedChange = { addToWhatsapp = it })
                Text("WhatsApp")
            }

            Button(
                onClick = { onPublish(mediaItems.toList(), title.trim(), shortName.trim(), pushToTelegram, addToWhatsapp) },
                enabled = canPublish,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create and publish")
            }
            if (mediaItems.size in 1..2) {
                Text(
                    text = "Pick at least 3 items -- both Telegram and WhatsApp require a 3-sticker minimum.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
