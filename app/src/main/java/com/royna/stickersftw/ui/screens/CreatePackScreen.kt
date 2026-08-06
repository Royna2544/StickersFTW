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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.data.ShortNameValidator
import com.royna.stickersftw.model.PickedMediaItem
import com.royna.stickersftw.model.PickedMediaKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePackScreen(
    onBack: () -> Unit,
    botUsername: String? = null,
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

    val shortNameResult = if (shortName.isBlank()) null else ShortNameValidator.validate(shortName, botUsername)
    val normalizedShortName = (shortNameResult as? ShortNameValidator.Result.Valid)?.baseName
    val shortNameValid = normalizedShortName != null
    val canPublish = mediaItems.size >= 3 && title.isNotBlank() && shortNameValid && (pushToTelegram || addToWhatsapp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_pack_label)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
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
                text = stringResource(R.string.create_pack_instructions),
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
                Text(pluralStringResource(R.plurals.pick_media_selected, mediaItems.size, mediaItems.size))
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
                                            contentDescription = stringResource(R.string.cd_video),
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
                                    label = { Text(stringResource(R.string.create_pack_emoji_label)) },
                                    singleLine = true,
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { mediaItems.removeAt(index) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_remove))
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
                label = { Text(stringResource(R.string.create_pack_title_label)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = shortName,
                onValueChange = { shortName = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.create_pack_short_name_label)) },
                supportingText = {
                    Text(
                        when (val result = shortNameResult) {
                            null, is ShortNameValidator.Result.Valid -> stringResource(R.string.create_pack_short_name_hint_valid)
                            is ShortNameValidator.Result.WrongBotSuffix ->
                                stringResource(R.string.create_pack_short_name_hint_wrong_bot, result.suffixBot)
                            is ShortNameValidator.Result.InvalidFormat ->
                                stringResource(R.string.create_pack_short_name_hint_invalid)
                        },
                    )
                },
                singleLine = true,
            )

            Text(stringResource(R.string.create_pack_publish_to), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pushToTelegram, onCheckedChange = { pushToTelegram = it })
                Text(stringResource(R.string.label_telegram))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = addToWhatsapp, onCheckedChange = { addToWhatsapp = it })
                Text(stringResource(R.string.label_whatsapp))
            }

            Button(
                onClick = {
                    normalizedShortName?.let {
                        onPublish(mediaItems.toList(), title.trim(), it, pushToTelegram, addToWhatsapp)
                    }
                },
                enabled = canPublish,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_create_and_publish))
            }
            if (mediaItems.size in 1..2) {
                Text(
                    text = stringResource(R.string.create_pack_min_items_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
