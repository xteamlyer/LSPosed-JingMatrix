package org.matrix.vector.manager.data.github

import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

/**
 * What the rail draws, in order.
 *
 * The rail is deliberately **not** a branch graph. This project squash-merges, so its history is
 * linear by construction — there were zero merge commits in the last 44 — and drawing lanes would
 * be inventing structure that does not exist. What the data *does* carry, and a uniform list
 * throws away, is time and the reader's own position in it. That is what these items encode.
 */
sealed interface FeedItem {

    /** A commit. The rail spans its full height; elapsed time is carried by [Gap] below it. */
    data class Commit(
        val commit: TimelineCommit,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : FeedItem

    /**
     * The elapsed time between two commits, as rail.
     *
     * A separate row rather than a trailing segment inside the commit row, because a commit row's
     * height is set by its text and would otherwise leave the line stopping short of the next
     * node — the rail visibly broke between same-day commits.
     */
    data class Gap(val days: Int, val afterSha: String) : FeedItem

    /**
     * The line between what the reader is running and what they are not.
     *
     * `versionCode` is `git rev-list --count`, so a commit's distance from HEAD is exactly its
     * version number — no guessing and no extra endpoint. Everything above this line is what an
     * update would actually bring, named commit by commit, which is the question a framework
     * user actually has.
     */
    data class InstalledMarker(
        val versionCode: Long,
        val commitsAhead: Int,
        /**
         * True when the installed build is *past* the head of master.
         *
         * That cannot happen to anyone running a published build, so it means the framework was
         * built locally or from another branch. Worth saying: the feed below is then not the
         * history of what is installed, and neither an issue report nor a bisect against it means
         * what the reader would assume.
         */
        val aheadOfMaster: Boolean = false,
    ) : FeedItem

    /**
     * Where the rail crosses into an earlier month, with what that month amounted to.
     *
     * A bare month name is just a scroll landmark. With the month's own totals it becomes the
     * summary layer of the timeline: you can read the project's shape by skimming the separators
     * without reading a single commit.
     */
    data class MonthMarker(
        val label: String,
        val commits: Int,
        val people: Int,
    ) : FeedItem



    /** Consecutive bot commits, folded. */
    data class Bots(val count: Int, val commits: List<TimelineCommit>) : FeedItem
}

object FeedLayout {

    /** Below this a gap is just normal cadence and gets no label. */
    const val QUIET_THRESHOLD_DAYS = 14

    fun build(feed: CommunityFeed, installedVersionCode: Long): List<FeedItem> {
        val visible = feed.commits.filterNot { it.isBot }
        if (visible.isEmpty()) return emptyList()

        val bots = feed.commits.filter { it.isBot }
        val items = mutableListOf<FeedItem>()

        // Only meaningful once both numbers are known, and only when the reader is actually
        // behind — telling someone who is up to date that they are up to date is noise.
        val commitsAhead =
            if (feed.totalCommits > 0 && installedVersionCode > 0) {
                (feed.totalCommits - installedVersionCode).toInt().coerceAtLeast(0)
            } else 0
        // Past the head of master: place it at the top rather than looking for a commit it could
        // sit above, because there is not one.
        val aheadOfMaster = feed.totalCommits > 0 && installedVersionCode > feed.totalCommits
        if (aheadOfMaster) {
            items +=
                FeedItem.InstalledMarker(
                    versionCode = installedVersionCode,
                    commitsAhead = (installedVersionCode - feed.totalCommits).toInt(),
                    aheadOfMaster = true,
                )
        }
        var markerPlaced = aheadOfMaster || commitsAhead <= 0

        var lastMonth: String? = null

        visible.forEachIndexed { index, commit ->
            if (!markerPlaced && commit.globalIndex <= installedVersionCode) {
                items += FeedItem.InstalledMarker(installedVersionCode, commitsAhead)
                markerPlaced = true
            }

            val month = monthLabel(commit.epochSeconds)
            if (month != lastMonth) {
                val inMonth = visible.filter { monthLabel(it.epochSeconds) == month }
                items +=
                    FeedItem.MonthMarker(
                        label = month,
                        commits = inMonth.size,
                        people =
                            inMonth
                                .flatMap { c -> c.authors.filterNot { it.isBot } }
                                .distinctBy { it.login.lowercase() }
                                .size,
                    )
                lastMonth = month
            }

            val older = visible.getOrNull(index + 1)
            items +=
                FeedItem.Commit(
                    commit = commit,
                    isFirst = index == 0,
                    isLast = older == null && bots.isEmpty(),
                )

            if (older != null) {
                val gapDays =
                    ((commit.epochSeconds - older.epochSeconds) / 86_400L).toInt().coerceAtLeast(0)
                items += FeedItem.Gap(gapDays, commit.sha)
            }
        }

        if (bots.isNotEmpty()) items += FeedItem.Bots(bots.size, bots)
        return items
    }

    /**
     * Gap in days to rail height.
     *
     * Square root rather than linear. Linear is the honest chart, but this project's gaps span
     * 0 to 76 days, so a literal scale spends a full screen of empty rail on one silence and
     * flattens every ordinary one-to-three-day gap into the same nothing. The root keeps short
     * gaps distinguishable, still shows a long one as visibly long, and the clamp stops any
     * single quiet stretch from dominating the scroll.
     */
    fun railHeightDp(gapDays: Int): Float =
        (MIN_GAP_DP + sqrt(gapDays.toFloat()) * SCALE).coerceAtMost(MAX_GAP_DP)

    const val MIN_GAP_DP = 8f
    const val MAX_GAP_DP = 120f
    private const val SCALE = 13f

    private fun monthLabel(epochSeconds: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        val month =
            cal.getDisplayName(Calendar.MONTH, Calendar.LONG_STANDALONE, Locale.getDefault())
                ?: cal.get(Calendar.MONTH).toString()
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val year = cal.get(Calendar.YEAR)
        return if (year == thisYear) month else "$month $year"
    }
}
