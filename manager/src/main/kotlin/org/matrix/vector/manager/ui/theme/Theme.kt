package org.matrix.vector.manager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.matrix.vector.manager.di.ServiceLocator

/** How the app resolves light vs dark. Persisted under the string keys used here. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

@Composable
fun VectorTheme(content: @Composable () -> Unit) {
    val settings = ServiceLocator.settings
    val modeKey by settings.themeMode.collectAsState()
    val dynamicRequested by settings.dynamicColor.collectAsState()
    val amoled by settings.amoledBlack.collectAsState()
    val seed by settings.seedColor.collectAsState()

    val dark =
        when (ThemeMode.from(modeKey)) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }

    val context = LocalContext.current
    // Dynamic colour is this app's default; the seed below applies before Android 12, or whenever
    // the user would rather choose the colour themselves than inherit their wallpaper's.
    val dynamic = dynamicRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var scheme =
        when {
            dynamic && dark -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            else -> remember(seed, dark) { SeedScheme.of(seed, dark) }
        }
    if (dark && amoled) scheme = scheme.toAmoled()

    MaterialExpressiveTheme(
        colorScheme = scheme,
        // The expressive motion scheme is what makes a state change feel caused rather than
        // scheduled. It drives the status indicator's shape morph and the nav transitions.
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
