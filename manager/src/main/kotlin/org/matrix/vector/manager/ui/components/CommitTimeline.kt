package org.matrix.vector.manager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.currentLocale
import org.matrix.vector.manager.data.github.TimelineCommit
import org.matrix.vector.manager.ui.theme.VectorMono

/**
 * One row of the commit rail.
 *
 * The rail is a real vertical line with a node on it, not a list of cards, because the thing being
 * shown is a history — continuity is the point.
 *
 * **Node fill marks authorship.** A commit written by someone other than the repository owner gets
 * a filled node and its author's name in the emphasis colour; the maintainer's own commits get a
 * hollow one. No badge and no label saying "community", which would read as a category rather than
 * a thank-you — the contribution simply stands out on the rail. This is the design's answer to
 * "encourage participation": the recognition is visible in the screen every user opens.
 */
@Composable
fun CommitRow(
    commit: TimelineCommit,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenCommit: (TimelineCommit) -> Unit,
    onOpenPullRequest: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val nodeColor = commit.railColor()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable { onOpenCommit(commit) }
    ) {
        Rail(isFirst = isFirst, isLast = isLast, nodeColor = nodeColor, filled = commit.isCommunity)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 18.dp)) {
            // The badges flow with the title text rather than sitting in a reserved column: a
            // fixed trailing column narrows every line of the subject, and the subject is the
            // thing worth reading. They are one inline slot rather than two, so the hash and the
            // pull-request badge can never be split across a line break — a wrap between them
            // reads as though the number belongs to the next commit.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val prLabel = commit.pullRequest?.let { "#$it" }

            val badgeSize =
                remember(commit.shortSha, prLabel) {
                    val sha = measurer.measure(commit.shortSha, VectorMono).size
                    val pr = prLabel?.let { measurer.measure(it, VectorMono).size }
                    // chip padding (10) + border, per chip, plus the gap between them
                    val width =
                        sha.width + CHIP_PAD_PX + (pr?.let { it.width + CHIP_PAD_PX + GAP_PX } ?: 0)
                    val height = maxOf(sha.height, pr?.height ?: 0) + CHIP_PAD_PX
                    width to height
                }

            val title = buildAnnotatedString {
                append(commit.subject)
                append("  ")
                appendInlineContent(BADGE_SLOT, commit.shortSha)
            }

            val inline =
                mapOf(
                    BADGE_SLOT to
                        InlineTextContent(
                            Placeholder(
                                width = with(density) { badgeSize.first.toDp().toSp() },
                                height = with(density) { badgeSize.second.toDp().toSp() },
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            )
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    modifier =
                                        Modifier.fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.surfaceContainerHigh)
                                            .padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        commit.shortSha,
                                        style = VectorMono,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                if (prLabel != null) {
                                    // Opens the discussion where participation happens, not just
                                    // a diff.
                                    Box(
                                        modifier =
                                            Modifier.fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .border(
                                                    1.dp,
                                                    colors.primary.copy(alpha = 0.4f),
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .clickable {
                                                    onOpenPullRequest(commit.pullRequest)
                                                }
                                                .padding(horizontal = 5.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(prLabel, style = VectorMono, color = colors.primary)
                                    }
                                }
                            }
                        }
                )

            Text(
                text = title,
                inlineContent = inline,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            // The subject and its attribution are separate thoughts; crowding them made
            // the row read as one dense block.
            Spacer(Modifier.height(7.dp))
            val credit =
                when (commit.coAuthors.size) {
                    0 -> commit.authorLogin
                    1 ->
                        stringResource(
                            R.string.home_with_coauthor,
                            commit.authorLogin,
                            commit.coAuthors.first().login,
                        )
                    else ->
                        stringResource(
                            R.string.home_with_coauthors,
                            commit.authorLogin,
                            commit.coAuthors.size,
                        )
                }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = credit,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight =
                        if (commit.isCommunity) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (commit.isCommunity) colors.primary else colors.onSurfaceVariant,
                )
                Text(
                    text = exactTime(commit.epochSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Consecutive bot commits collapse into one expandable row.
 *
 * 56 of the last 300 commits on this repository are dependabot `Bump …`. Left inline they bury the
 * human work the section exists to celebrate.
 */
@Composable
fun BotBundleRow(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    children: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
            Rail(
                isFirst = false,
                isLast = isLast && !expanded,
                nodeColor = colors.outline,
                filled = false,
                nodeSize = 8.dp,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.home_bumps, count),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(bottom = 18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) { Column { children() } }
    }
}

@Composable
private fun Rail(
    isFirst: Boolean,
    isLast: Boolean,
    nodeColor: Color,
    filled: Boolean,
    nodeSize: androidx.compose.ui.unit.Dp = 11.dp,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // The line fills the row's whole height. Sizing it to a fixed length instead left it stopping
    // short of the next node whenever a commit's text ran to two lines, so the rail visibly broke.
    Column(
        modifier = Modifier.width(22.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(2.dp)
                .height(7.dp)
                .background(if (isFirst) Color.Transparent else line)
        )
        Box(
            modifier =
                Modifier.size(nodeSize)
                    .clip(CircleShape)
                    .background(if (filled) nodeColor else MaterialTheme.colorScheme.surface)
                    .border(2.dp, nodeColor, CircleShape)
        )
        if (!isLast) {
            Box(Modifier.width(2.dp).weight(1f).background(line))
        }
    }
}

/**
 * The elapsed time between two commits, drawn as rail.
 *
 * This is where the timeline stops being a list. Two commits on the same day sit almost touching;
 * a fortnight apart and the line visibly stretches, so the project's rhythm — bursts of work,
 * stretches of quiet — is legible without reading a single date. A long silence is named, because
 * empty rail on its own is ambiguous and could read as a layout gap.
 */
@Composable
fun GapRow(days: Int, heightDp: Float, showLabel: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().height(heightDp.dp)) {
        Column(
            modifier = Modifier.width(22.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.width(2.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        if (showLabel) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = pluralStringResource(R.plurals.home_quiet_days, days, days),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * The rail encodes **authorship only**, not commit type.
 *
 * Colouring by type was tried and dropped: 52 of the last 300 subjects begin with "Fix", so an
 * error-coloured node made a perfectly healthy history read as a wall of alarm. It was also
 * redundant — this project writes plain imperative subjects, so the first word of the line the
 * user is already reading *is* the type. Saying it twice, once in a colour that means "something
 * is wrong", was worse than not saying it.
 *
 * [CommitKind] is still parsed and kept on the model, for filtering later.
 */
@Composable
private fun TimelineCommit.railColor(): Color =
    if (isCommunity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline


/**
 * The line between the build the reader is running and the commits they are not.
 *
 * This is the one thing on the page that is about *them*. `versionCode` is `git rev-list --count`,
 * so a build's position in history is exact — everything above this marker is precisely what an
 * update would bring, named commit by commit rather than summarised as "a new version".
 */
@Composable
fun InstalledMarkerRow(
    versionCode: Long,
    commitsAhead: Int,
    aheadOfMaster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A build past the head of master is not a position on this timeline, it is a warning: it was
    // built locally or from another branch, so the history below is not the history of what is
    // running. Drawn in the caution colour rather than the accent for exactly that reason.
    val accent = if (aheadOfMaster) colors.tertiary else colors.primary
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(22.dp).height(2.dp).background(accent),
            contentAlignment = Alignment.Center,
        ) {}
        Spacer(Modifier.width(8.dp))
        Text(
            text =
                if (aheadOfMaster) stringResource(R.string.home_custom_build)
                else pluralStringResource(R.plurals.home_commits_ahead, commitsAhead, commitsAhead),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_your_build, versionCode),
            style = VectorMono,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.45f)))
    }
}

/**
 * A month boundary, carrying what that month amounted to.
 *
 * A bare month name is only a scroll landmark. With its own totals the separator becomes the
 * timeline's summary layer — the project's shape is readable by skimming the separators alone,
 * without reading a single commit subject.
 */
@Composable
fun MonthMarkerRow(month: Int, year: Int?, commits: Int, people: Int, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val label =
        remember(month, year, locale) {
            val cal = Calendar.getInstance(locale).apply { set(Calendar.MONTH, month) }
            val name =
                cal.getDisplayName(Calendar.MONTH, Calendar.LONG_STANDALONE, locale)
                    ?: month.toString()
            if (year == null) name else "$name $year"
        }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.width(28.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                stringResource(
                    R.string.home_month_stats,
                    pluralStringResource(R.plurals.home_commit_count, commits, commits),
                    pluralStringResource(R.plurals.home_people_count_plain, people, people),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

private const val BADGE_SLOT = "badges"

/** Horizontal padding inside a chip, and the gap between the two, in raw pixels. */
private const val CHIP_PAD_PX = 26
private const val GAP_PX = 12
