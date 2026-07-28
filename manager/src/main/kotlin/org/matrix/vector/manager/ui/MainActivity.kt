package org.matrix.vector.manager.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.manager.ui.screens.splash.SplashGate
import org.matrix.vector.manager.ui.theme.LocalizedContent
import org.matrix.vector.manager.ui.theme.VectorTheme

/**
 * The only activity.
 *
 * Parasitically, every activity has to be tracked by hand by the zygisk hooker — it captures and
 * restores their saved state itself, because `system_server` does not know these spoofed activities
 * exist. One activity is therefore not a style preference, it is the shape the injection model
 * wants.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate. Handing off from the platform splash removes the frame of
        // unthemed window the previous build showed between the system splash and the Compose one.
        val splash = installSplashScreen()

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Idempotent, and safe whether or not the daemon already called Constants.setBinder.
        ServiceLocator.attach(this)

        // Coil is configured explicitly rather than through its manifest hooks: parasitically this
        // app's manifest is never installed, so nothing that self-registers there ever runs.
        // It shares the one OkHttp client, which carries the DoH configuration and the disk cache.
        SingletonImageLoader.setSafe { platformContext: PlatformContext ->
            ImageLoader.Builder(platformContext)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { ServiceLocator.http }))
                }
                .build()
        }

        // Keep the platform splash up only until the first frame is ready to draw; the Compose
        // splash then plays and decides for itself when the daemon has been given long enough.
        splash.setKeepOnScreenCondition { false }

        setContent { LocalizedContent { VectorTheme { SplashGate { VectorApp() } } } }
    }
}
