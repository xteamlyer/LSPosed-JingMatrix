package org.matrix.vector.manager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.matrix.vector.manager.R

/**
 * "4 days ago", in the user's language.
 *
 * Coarse on purpose. The commit feed answers "is this project alive and who is working on it",
 * which minutes and hours do not help with, and a coarse label stays correct for longer without
 * recomposing.
 */
@Composable
fun relativeTime(epochSeconds: Long): String {
    val context = LocalContext.current
    val days = ((System.currentTimeMillis() / 1000 - epochSeconds) / 86_400L).toInt()
    return when {
        // Today is the one case where the exact hour is worth more than the coarse label: it is
        // how someone watching a fix land tells "just now" from "this morning". Rendered in the
        // device's own locale and 12/24-hour preference.
        days <= 0 ->
            android.text.format.DateFormat.getTimeFormat(context)
                .format(java.util.Date(epochSeconds * 1000))
        days == 1 -> stringResource(R.string.time_yesterday)
        days < 7 -> context.resources.getQuantityString(R.plurals.time_days_ago, days, days)
        days < 31 ->
            (days / 7).let { context.resources.getQuantityString(R.plurals.time_weeks_ago, it, it) }
        else ->
            (days / 30).let {
                context.resources.getQuantityString(R.plurals.time_months_ago, it, it)
            }
    }
}

/** Compact counts for the project footer: 11905 becomes "11.9k". */
fun compactCount(value: Int): String =
    when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fk", value / 1000f)
        else -> String.format(java.util.Locale.getDefault(), "%.1fM", value / 1_000_000f)
    }

/**
 * The precise moment a commit landed, in the device's locale and 12/24-hour preference.
 *
 * The timeline already carries *approximate* time structurally — the rail's length is the elapsed
 * gap, and the month separators give the coarse position. So the text is free to be exact, which
 * is what someone comparing a commit against their own build actually needs. A relative label
 * would duplicate what the rail already says, less precisely.
 */
@Composable
fun exactTime(epochSeconds: Long): String {
    val context = LocalContext.current
    val millis = epochSeconds * 1000
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(millis))

    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }

    val sameDay =
        now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return time

    val flags =
        if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
            android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                android.text.format.DateUtils.FORMAT_NO_YEAR
        } else {
            android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                android.text.format.DateUtils.FORMAT_SHOW_YEAR
        }
    val date = android.text.format.DateUtils.formatDateTime(context, millis, flags)
    return "$date $time"
}
