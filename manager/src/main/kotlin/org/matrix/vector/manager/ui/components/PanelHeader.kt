package org.matrix.vector.manager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * The top of a list panel: a title, its actions, and a line of state under either of them.
 *
 * Modules, Store and Logs are the same kind of screen — a title, a few actions, a search field and
 * a long list — and each had grown its own header: two hand-rolled rows and a `TopAppBar`, of three
 * different heights. Switching tabs therefore moved the search field, which is the one control a
 * reader's thumb learns the position of, and moving it is the sort of thing that is felt long
 * before it is noticed.
 *
 * So the height is **fixed** rather than measured, and the same for every panel whatever it puts
 * inside. A subtitle that appears and disappears — the log's line counter, the Store's update count
 * — then costs nothing below it. What the panels do *not* share is where that subtitle sits: the
 * module list keeps its count under the backup and restore icons on the right, where it reads as
 * one idea with them, and the log keeps its counter under the title on the left, because it is a
 * property of the file rather than of the actions.
 *
 * The scope editor is deliberately not one of these. It is a screen you arrive at from somewhere
 * else and leave again, so it has a back arrow and a name to carry, and the shape of the panels you
 * navigate between is the wrong shape for it.
 */
@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    startSubtitle: (@Composable () -> Unit)? = null,
    endSubtitle: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .height(PANEL_HEADER_HEIGHT)
                .padding(start = 20.dp, end = 8.dp, top = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions?.invoke(this)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(modifier = Modifier.weight(1f)) { startSubtitle?.invoke() }
            endSubtitle?.invoke()
        }
    }
}

/**
 * Chosen from the tallest of the three, so nothing has to shrink to fit and the search field below
 * lands on the same pixel on every panel.
 */
val PANEL_HEADER_HEIGHT = 106.dp
