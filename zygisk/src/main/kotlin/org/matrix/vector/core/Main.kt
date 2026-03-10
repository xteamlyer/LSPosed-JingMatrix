package org.matrix.vector.core

import android.os.IBinder
import android.os.Process
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedModuleInterface
import org.lsposed.lspd.core.ApplicationServiceClient.serviceClient
import org.lsposed.lspd.core.Startup
import org.lsposed.lspd.deopt.PrebuiltMethodsDeopter
import org.lsposed.lspd.hooker.HandleSystemServerProcessHooker
import org.lsposed.lspd.impl.LSPosedContext
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.util.Utils
import org.matrix.vector.BuildConfig
import org.matrix.vector.ParasiticManagerHooker
import org.matrix.vector.ParasiticManagerSystemHooker

/** Main entry point for the Java-side loader, invoked via JNI from the Vector Zygisk module. */
object Main {

    /**
     * Shared initialization logic for both System Server and Application processes.
     *
     * @param isSystem True if this is the system_server process.
     * @param isLateInject True if Zygisk APIs are not invoked via hooks
     * @param niceName The process name (e.g., package name or "system").
     * @param appDir The application's data directory.
     * @param binder The Binder token associated with the application service.
     */
    @JvmStatic
    fun forkCommon(isSystem: Boolean, isLateInject: Boolean, niceName: String, appDir: String?, binder: IBinder) {
        // Initialize system-specific resolution hooks if in system_server
        if (isSystem) {
            ParasiticManagerSystemHooker.start()
        }

        // Initialize Xposed bridge components
        val appService = ILSPApplicationService.Stub.asInterface(binder)
        Startup.initXposed(isSystem, niceName, appDir, appService)

        // Configure logging levels from the service client
        runCatching { Utils.Log.muted = serviceClient.isLogMuted }
            .onFailure { t -> Utils.logE("Failed to configure logs from service", t) }

        // Check if this process is the designated Vector Manager.
        // If so, we perform "parasitic" injection into a host (com.android.shell)
        // and terminate further standard Xposed loading for this specific process.
        if (niceName == BuildConfig.ManagerPackageName && ParasiticManagerHooker.start()) {
            Utils.logI("Parasitic manager loaded into host, skipping standard bootstrap.")
            return
        }

        // Standard Xposed module loading for third-party apps
        Utils.logI("Loading Vector/Xposed for $niceName (UID: ${Process.myUid()})")
        Startup.bootstrapXposed()

        if (isSystem && isLateInject) {
            Utils.logD("Manually triggering system_server module load for late injection")
            try {
                // 1. Steal the correct SystemServer ClassLoader from a live service.
                // Because we inject late via ptrace, ActivityManagerService is already fully
                // loaded.
                val activityService = android.os.ServiceManager.getService("activity")
                if (activityService == null) {
                    Utils.logE("Activity service not found! Cannot get SystemServer ClassLoader.")
                    return
                }
                val systemServerCL = activityService.javaClass.classLoader

                // Maintain state consistency for the rest of the Vector framework
                HandleSystemServerProcessHooker.systemServerCL = systemServerCL

                // Deopt system server methods (if required by LSPlant hooks)
                runCatching { PrebuiltMethodsDeopter.deoptSystemServerMethods(systemServerCL) }
                    .onFailure { Utils.logE("Failed to deopt system server methods", it) }

                // 2. Trigger the callback to load the parasitic manager
                HandleSystemServerProcessHooker.callback?.onSystemServerLoaded(systemServerCL)

                // 3. Emulate StartBootstrapServicesHooker.before() payload synchronously
                XposedInit.loadedPackagesInProcess.add("android")

                val lpparam =
                    XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks).apply {
                        packageName = "android"
                        processName = "android"
                        classLoader = systemServerCL
                        appInfo = null
                        isFirstApplication = true
                    }

                Utils.logD("Firing XC_LoadPackage.callAll for android...")
                XC_LoadPackage.callAll(lpparam)

                // 4. Fire modern LibXposed callbacks
                LSPosedContext.callOnSystemServerLoaded(
                    object : XposedModuleInterface.SystemServerLoadedParam {
                        override fun getClassLoader(): ClassLoader = systemServerCL!!
                    }
                )

                Utils.logI("Late system_server injection successfully completed.")
            } catch (t: Throwable) {
                Utils.logE("Error during late system_server bootstrap", t)
            }
        }
    }
}
