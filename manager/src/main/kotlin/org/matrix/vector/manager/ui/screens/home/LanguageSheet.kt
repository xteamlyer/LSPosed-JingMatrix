package org.matrix.vector.manager.ui.screens.home

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import org.matrix.vector.manager.ui.theme.translatorsFor
import org.matrix.vector.manager.ui.theme.Translator
import org.matrix.vector.manager.ui.theme.CROWDIN_URL
import androidx.compose.material3.ListItem
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.theme.availableLocales
import org.matrix.vector.manager.ui.theme.nativeName

/**
 * The language, in the languages themselves.
 *
 * Every row is written in its own language and its own script — Deutsch, Русский, 日本語 — because a
 * list of English names is unreadable to precisely the person looking for a language they can read.
 * The English name follows underneath, since the reader might be choosing on behalf of someone else,
 * or checking they picked the right one.
 *
 * The list is read from the APK's own resources, so a language appears here the moment a translator
 * lands its folder; nothing to remember to update.
 *
 * Choosing does not close the sheet or restart anything. The strings behind it change immediately
 * and the row itself swells into place, so the effect of the choice is visible while the choice is
 * still being made — which is also the honest way to preview a language you may not be able to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSheet(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val settings = ServiceLocator.settings
    val current by settings.appLocale.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locales = remember { availableLocales() }
    // No skipPartiallyExpanded. Passing it removed the half-height stop, which is the only thing
    // a drag on a sheet can *do* other than dismiss it — so a sheet taller than half the screen
    // opened at full height and could not be made smaller. Left at the default, Material adds the
    // stop only when the content is actually taller than half the screen, so short sheets still
    // open at their own height and nothing gains a useless drag.
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
LocalizedOverlay {

        // Title and invitation on one line. They were two rows, each carrying the same Translate
        // glyph forty dp apart, and the invitation — a two-line list item — outweighed the sheet's
        // own title. Worse, at the half-height rest it spent two of the five visible rows before
        // the reader reached a single language. One row keeps it permanently visible at any sheet
        // height and gives the list its space back.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.language_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            val invitation = stringResource(R.string.language_help)
            AssistChip(
                onClick = { onOpen(CROWDIN_URL) },
                label = { Text(stringResource(R.string.language_help_short)) },
                trailingIcon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                // The chip is short so it fits beside the title in every language; the full
                // sentence is what a screen reader should hear, because "Translate" on its own
                // could as easily mean translating something *in* the app.
                modifier = Modifier.semantics { contentDescription = invitation },
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            item {
                LanguageRow(
                    native = stringResource(R.string.language_system),
                    english = stringResource(R.string.language_system_summary),
                    selected = current.isBlank(),
                    onClick = { settings.setAppLocale("") },
                )
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            items(locales, key = { it.toLanguageTag() }) { locale ->
                LanguageRow(
                    native = locale.nativeName(),
                    english = locale.getDisplayName(Locale.ENGLISH),
                    selected = current == locale.toLanguageTag(),
                    onClick = { settings.setAppLocale(locale.toLanguageTag()) },
                    credits = translatorsFor(locale),
                    onOpen = onOpen,
                )
            }
        }
    }
}
}

/**
 * One language.
 *
 * The selected row is drawn rather than ticked: it lifts onto the primary container and its marker
 * springs out. On a list where most rows are in a script the reader cannot parse, a small tick in
 * the margin is easy to lose — the shape of the row itself has to carry the answer.
 */
@Composable
private fun LanguageRow(
    native: String,
    english: String,
    selected: Boolean,
    onClick: () -> Unit,
    credits: List<Translator> = emptyList(),
    onOpen: (String) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val container by
        animateColorAsState(
            if (selected) colors.primaryContainer else Color.Transparent,
            label = "language container",
        )
    val markScale by
        animateFloatAsState(
            if (selected) 1f else 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "language mark",
        )

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(container)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = native,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = english,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (selected) colors.onPrimaryContainer.copy(alpha = 0.7f)
                    else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only for languages a person put their name to, so the common row keeps its two
            // lines. A chip rather than plain text because it is a target: tapping it opens the
            // translator's page while a tap anywhere else on the row still picks the language.
            if (credits.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    credits.forEach { person ->
                        AssistChip(
                            onClick = { person.url?.let(onOpen) },
                            enabled = person.url != null,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.language_translated_by,
                                        person.name,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
        Box(
            modifier =
                Modifier.size(26.dp)
                    .scale(markScale)
                    .clip(CircleShape)
                    .background(colors.primary)
                    .border(0.dp, Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** The icon that opens it, kept next to the palette because both govern how the app presents itself. */
val LanguageIcon = Icons.Rounded.Language
