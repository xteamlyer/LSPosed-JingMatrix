package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The three pieces every settings sheet in this app is built from.
 *
 * They began inside the appearance sheet and were copied outwards, which is how two sheets end up
 * looking *almost* the same — the tell is a heading indented differently, or a switch row whose
 * subtitle wraps at another width. Shared here so that a new sheet inherits the pattern rather than
 * re-deriving it, and so that changing the pattern changes every sheet at once.
 */
@Composable
fun SheetHeading(text: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A row of choices, wrapping onto as many lines as it needs.
 *
 * This scrolled sideways at first, on the theory that a chip which reflows moves every chip after
 * it, so an option sits somewhere different in each language. True, but it is the lesser problem:
 * scrolling *hides* the options past the edge, and an option nobody knows about is worse than one
 * that moved. In a sheet the vertical room costs nothing, so everything is shown at once.
 */
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/**
 * One switch, with the sentence that says what turning it on costs.
 *
 * The whole row is the target, not just the switch, and the switch itself takes no callback so a
 * tap cannot be counted twice.
 */
@Composable
fun ToggleRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * One thing the sheet can do.
 *
 * The same shape as [ToggleRow] minus the switch, so a sheet that mixes settings and actions still
 * reads as one list rather than two borrowed idioms.
 */
@Composable
fun SheetAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = tint ?: LocalContentColor.current)
        },
    )
}
