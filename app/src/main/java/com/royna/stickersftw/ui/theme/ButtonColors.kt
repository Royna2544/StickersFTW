package com.royna.stickersftw.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** [ButtonDefaults.buttonColors] with a legible disabled label.
 *
 * Material3's default disabled label is `onSurface` at 38% opacity, drawn on
 * a container of `onSurface` at 12%. Over the surfaceVariant cards this app
 * puts its buttons on, that composites to roughly 2.2:1 -- low enough that a
 * disabled "Save" reads as an empty pill. An opaque `onSurfaceVariant` label
 * lifts it to about 5.5:1 in light and 6:1 in dark; the muted container is
 * left alone, so it still carries the disabled signal on its own and the
 * enabled state is untouched.
 *
 * Applies to every filled [androidx.compose.material3.Button] in the app
 * that has a disabled state. */
@Composable
fun appButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
