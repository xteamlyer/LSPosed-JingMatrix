package org.matrix.vector

import android.annotation.SuppressLint
import android.app.ProfilerInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import io.github.libxposed.api.XposedInterface
import org.lsposed.lspd.hooker.HandleSystemServerProcessHooker
import org.lsposed.lspd.impl.LSPosedHelper
import org.lsposed.lspd.util.Utils
import org.matrix.vector.service.BridgeService

/**
 * Handles System-Server side logic for the Parasitic Manager.
 *
 * When a user tries to open the LSPosed Manager, the system normally wouldn't know how to handle it
 * because it isn't "installed." This class intercepts the activity resolution and tells the system
 * to launch it in a special process.
 */
class ParasiticManagerSystemHooker : HandleSystemServerProcessHooker.Callback {

    companion object {
        @JvmStatic
        fun start() {
            // Register this class as the handler for system_server initialization
            HandleSystemServerProcessHooker.callback = ParasiticManagerSystemHooker()
        }
    }

    /** Intercepts Activity resolution in the System Server. */
    object Hooker : XposedInterface.Hooker {
        @JvmStatic
        fun after(callback: XposedInterface.AfterHookCallback) {
            val intent = callback.args[0] as? Intent ?: return

            // Check if this intent is meant for the LSPosed Manager
            if (!intent.hasCategory(BuildConfig.ManagerPackageName + ".LAUNCH_MANAGER")) return

            val result = callback.result as? ActivityInfo ?: return

            // We only intercept if it's currently resolving to the shell/fallback
            if (result.packageName != BuildConfig.InjectedPackageName) return

            // --- Redirection Logic ---
            // We create a copy of the ActivityInfo to avoid polluting the system's cache.
            val redirectedInfo =
                ActivityInfo(result).apply {
                    // Force the manager to run in its own dedicated process name
                    processName = BuildConfig.ManagerPackageName

                    // Set a standard theme so transition animations work correctly
                    theme = android.R.style.Theme_DeviceDefault_Settings

                    // Ensure the activity isn't excluded from recents by host flags
                    flags =
                        flags and
                            (ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS or
                                    ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS)
                                .inv()
                }

            // Notify the bridge service that we are about to start the manager
            BridgeService.getService()?.preStartManager()

            // Replace the original ResolveInfo with our parasitic one
            callback.result = redirectedInfo
        }
    }

    @SuppressLint("PrivateApi")
    override fun onSystemServerLoaded(classLoader: ClassLoader) {
        runCatching {
                // Android versions change the name of the internal class responsible for activity
                // tracking.
                // We check the most likely candidates based on API levels (9.0 through 14+).
                val supervisorClass =
                    try {
                        // Android 12.0 - 14+
                        Class.forName(
                            "com.android.server.wm.ActivityTaskSupervisor",
                            false,
                            classLoader,
                        )
                    } catch (e: ClassNotFoundException) {
                        try {
                            // Android 10 - 11
                            Class.forName(
                                "com.android.server.wm.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        } catch (e2: ClassNotFoundException) {
                            // Android 8.1 - 9
                            Class.forName(
                                "com.android.server.am.ActivityStackSupervisor",
                                false,
                                classLoader,
                            )
                        }
                    }

                // Hook the resolution method to inject our redirection logic
                LSPosedHelper.hookMethod(
                    Hooker::class.java,
                    supervisorClass,
                    "resolveActivity",
                    Intent::class.java,
                    ResolveInfo::class.java,
                    Int::class.javaPrimitiveType,
                    ProfilerInfo::class.java,
                )

                Utils.logD("Successfully hooked Activity Supervisor for Manager redirection.")
            }
            .onFailure { Utils.logE("Failed to hook system server activity resolution", it) }
    }
}
