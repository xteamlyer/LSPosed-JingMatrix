package org.matrix.vector.manager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The platform type scale, with one deliberate deviation: identifiers are monospaced.
 *
 * Commit SHAs, version codes, package names and log lines are things a user compares character by
 * character. Proportional digits make that harder, so [VectorMono] is used for them rather than
 * bending the body styles.
 */
val VectorMono: TextStyle =
    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 0.sp)

/** Log bodies: monospace, tighter line height than the body scale, and small. */
val VectorLogLine: TextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
    )

/**
 * Section headers on Home read as a magazine front page rather than a settings list, so they get
 * extra tracking and weight against the default `titleMedium`.
 */
@Composable
fun sectionHeaderStyle(base: Typography): TextStyle = remember(base) {
    base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}
