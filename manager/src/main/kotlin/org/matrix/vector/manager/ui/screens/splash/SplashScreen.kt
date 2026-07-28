package org.matrix.vector.manager.ui.screens.splash
import android.util.Log
import org.matrix.vector.manager.Constants

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.vector.manager.R
import org.matrix.vector.manager.di.ServiceLocator
import kotlinx.coroutines.flow.first

/** How long the animation itself needs, so the statue is never cut off mid-fade. */
private const val ANIMATION_MS = 800L

/** The longest we wait on the daemon before showing the UI anyway, in its "not activated" state. */
private const val DAEMON_TIMEOUT_MS = 2_500L

/**
 * The Winged Victory, fading and scaling in exactly as designed — Vector, from *Victoria*.
 *
 * The behaviour behind it changed in two ways, both of which the fixed 1.5-second delay it
 * replaced got wrong:
 * - It **waits on the daemon**, not on a timer. The old delay's comment claimed it gave the binder
 *   time to connect, but it awaited nothing; a fast device paid the full 1.5 s and a slow one
 *   arrived at Home before the daemon was up and showed "Not Activated" incorrectly.
 * - It waits for the animation to finish too, so gating on a binder that resolves instantly does
 *   not produce a flash of half-drawn artwork.
 */
@Composable
fun SplashGate(content: @Composable () -> Unit) {
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Race the two: the artwork's own duration, and the daemon handshake with a ceiling.
        val bound =
            withTimeoutOrNull(DAEMON_TIMEOUT_MS) { ServiceLocator.service.first { it != null } }
        if (bound == null) {
            Log.w(
                Constants.TAG,
                "splash: no daemon binder after ${DAEMON_TIMEOUT_MS}ms, continuing unactivated",
            )
        }
        delay(ANIMATION_MS)
        ready = true
    }

    Crossfade(targetState = ready, animationSpec = tween(320), label = "splashHandoff") { done ->
        if (done) content() else WingedVictory()
    }
}

/** The splash artwork, also summoned by the header's easter egg. */
@Composable
fun WingedVictory() {
    var started by remember { mutableStateOf(false) }
    val alpha by
        animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(durationMillis = ANIMATION_MS.toInt()),
            label = "splashAlpha",
        )
    val scale by
        animateFloatAsState(
            targetValue = if (started) 1f else 0.8f,
            animationSpec = tween(durationMillis = ANIMATION_MS.toInt()),
            label = "splashScale",
        )

    val appName = stringResource(R.string.app_name)

    LaunchedEffect(Unit) { started = true }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .semantics { contentDescription = appName },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_winged_victory),
            contentDescription = null,
            tint = Color.Unspecified,
            // The artwork occupies only a slice of its square viewport, so sizing by width gave
            // a statue a third the size intended. Sized by the smaller dimension instead, which
            // holds on both portrait and landscape without stretching or clipping.
            modifier = Modifier.fillMaxSize(0.92f).scale(scale).alpha(alpha),
        )
    }
}
