package org.matrix.vector.manager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.currentLocale

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

/**
 * Compact counts for the project footer: 11905 becomes "11.9k".
 *
 * The locale is passed in rather than read from `Locale.getDefault()`, which is the *process*
 * default and stays the host app's: a reader on a French phone who has set the app to English was
 * being shown "11,9k".
 */
fun compactCount(value: Int, locale: Locale): String =
    when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(locale, "%.1fk", value / 1000f)
        else -> String.format(locale, "%.1fM", value / 1_000_000f)
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

    // Not DateUtils, which was the first version of this: its formatting runs through
    // `Locale.getDefault()` regardless of the context handed to it, so the month abbreviation
    // stayed in the phone's language while everything around it followed the app's. Asking for the
    // best pattern for a locale and formatting with it keeps the same shape — abbreviated month,
    // year only when it is not this one — and actually honours the choice.
    val locale = currentLocale()
    val skeleton = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) "MMMd" else "yMMMd"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    val date = java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(millis))
    return "$date $time"
}
