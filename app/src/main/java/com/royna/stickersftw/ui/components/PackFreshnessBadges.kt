package com.royna.stickersftw.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.royna.stickersftw.R
import com.royna.stickersftw.model.TelegramFreshnessState
import com.royna.stickersftw.model.WhatsappFreshnessState
import com.royna.stickersftw.ui.theme.PositiveGreen

/** Compact, explicit publish-state badges shared by pack cards and detail. */
@Composable
fun WhatsappFreshnessBadge(
    state: WhatsappFreshnessState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        when (state) {
            WhatsappFreshnessState.NotAdded -> R.string.freshness_whatsapp_not_added
            WhatsappFreshnessState.Current -> R.string.freshness_whatsapp_current
            WhatsappFreshnessState.NeedsRefresh -> R.string.freshness_whatsapp_needs_refresh
        },
    )
    val (statusIcon, color) = when (state) {
        WhatsappFreshnessState.NotAdded -> Icons.Rounded.RemoveCircleOutline to
            MaterialTheme.colorScheme.onSurfaceVariant
        WhatsappFreshnessState.Current -> Icons.Rounded.Check to PositiveGreen
        WhatsappFreshnessState.NeedsRefresh -> Icons.Rounded.Refresh to MaterialTheme.colorScheme.error
    }
    TargetFreshnessBadge(
        label = label,
        platformIcon = Icons.Rounded.Workspaces,
        statusIcon = statusIcon,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun TelegramFreshnessBadge(
    state: TelegramFreshnessState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        when (state) {
            TelegramFreshnessState.NotPushed -> R.string.freshness_telegram_not_pushed
            TelegramFreshnessState.Partial -> R.string.freshness_telegram_partial
            TelegramFreshnessState.Current -> R.string.freshness_telegram_current
            TelegramFreshnessState.OutOfDate -> R.string.freshness_telegram_out_of_date
        },
    )
    val (statusIcon, color) = when (state) {
        TelegramFreshnessState.NotPushed -> Icons.Rounded.RemoveCircleOutline to
            MaterialTheme.colorScheme.onSurfaceVariant
        TelegramFreshnessState.Partial -> Icons.Rounded.Schedule to MaterialTheme.colorScheme.secondary
        TelegramFreshnessState.Current -> Icons.Rounded.Check to PositiveGreen
        TelegramFreshnessState.OutOfDate -> Icons.Rounded.WarningAmber to MaterialTheme.colorScheme.error
    }
    TargetFreshnessBadge(
        label = label,
        platformIcon = Icons.AutoMirrored.Rounded.Send,
        statusIcon = statusIcon,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun TargetFreshnessBadge(
    label: String,
    platformIcon: ImageVector,
    statusIcon: ImageVector,
    color: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(platformIcon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Icon(statusIcon, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}
