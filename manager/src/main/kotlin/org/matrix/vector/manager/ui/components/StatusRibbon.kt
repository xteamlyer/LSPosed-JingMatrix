package org.matrix.vector.manager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.VectorMono

/** The three states the framework can be in, plus the moment before we know. */
enum class FrameworkState {
    Checking,
    Active,
    Degraded,
    Inactive,
}

/**
 * The status ribbon: one row, the whole width, tappable.
 *
 * Deliberately a ribbon rather than the legacy manager's 200 dp hero card. Once a user knows the
 * framework works, restating it at that size on every launch spends the most valuable screen space
 * on the least new information. Everything the hero used to show moved into the system status
 * screen this opens.
 *
 * The leading indicator is the one place in the app where **shape** carries meaning. That matters
 * because this app defaults to Material You: the user's wallpaper picks the hues, so colour alone
 * can never be trusted to signal state. Shape, icon and label all move together.
 */
@Composable
fun StatusRibbon(
    state: FrameworkState,
    version: String?,
    apiVersion: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            when (state) {
                FrameworkState.Active -> colors.primaryContainer
                FrameworkState.Degraded -> colors.tertiaryContainer
                FrameworkState.Inactive -> colors.errorContainer
                FrameworkState.Checking -> colors.surfaceContainer
            },
            label = "statusContainer",
        )
    val onContainer by
        animateColorAsState(
            when (state) {
                FrameworkState.Active -> colors.onPrimaryContainer
                FrameworkState.Degraded -> colors.onTertiaryContainer
                FrameworkState.Inactive -> colors.onErrorContainer
                FrameworkState.Checking -> colors.onSurfaceVariant
            },
            label = "statusOnContainer",
        )

    val label =
        stringResource(
            when (state) {
                FrameworkState.Active -> R.string.status_active
                FrameworkState.Degraded -> R.string.status_degraded
                FrameworkState.Inactive -> R.string.status_inactive
                FrameworkState.Checking -> R.string.status_checking
            }
        )

    val detail =
        buildList {
                version?.let { add(it) }
                apiVersion?.let { add("API $it") }
            }
            .joinToString("  ·  ")

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusIndicator(state = state, tint = onContainer)
            Column(Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = VectorMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.status_open_details),
            )
        }
    }
}

/**
 * The indicator itself. Its corner radius animates between three values — a rounded square when
 * active, a softer cookie-ish form when degraded, a full circle when inactive — so the transition
 * between states is legible as motion, not just as a colour swap.
 *
 * When active it breathes: a slow, low-amplitude scale that reads as "running" at a glance and
 * stops entirely in the other two states, so stillness itself means something.
 */
@Composable
private fun StatusIndicator(state: FrameworkState, tint: Color) {
    val cornerPercent by
        animateFloatAsState(
            when (state) {
                FrameworkState.Active -> 32f
                FrameworkState.Degraded -> 42f
                else -> 50f
            },
            label = "statusCorner",
        )

    val breathing = rememberInfiniteTransition(label = "statusBreath")
    val breathScale by
        breathing.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec =
                infiniteRepeatable(tween(durationMillis = 1800), RepeatMode.Reverse),
            label = "statusBreathScale",
        )

    val icon =
        when (state) {
            FrameworkState.Active -> Icons.Rounded.Check
            FrameworkState.Degraded -> Icons.Rounded.PriorityHigh
            FrameworkState.Inactive -> Icons.Rounded.Close
            FrameworkState.Checking -> null
        }

    Box(
        modifier =
            Modifier.size(40.dp)
                .scale(if (state == FrameworkState.Active) breathScale else 1f)
                .clip(RoundedCornerShape(percent = cornerPercent.toInt()))
                .background(tint.copy(alpha = 0.16f))
                .semantics { contentDescription = "" },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}
