package org.matrix.vector.manager.ipc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.lsposed.lspd.IFrameworkInstallCallback
import android.content.Intent
import org.lsposed.lspd.ILSPManagerService

/**
 * Safely wraps synchronous Binder transactions into asynchronous Kotlin Coroutines. Ensures the
 * main UI thread is never blocked by IPC delays or daemon deadlocks.
 */
class DaemonClient(private val serviceState: StateFlow<ILSPManagerService?>) {

    val service: ILSPManagerService?
        get() = serviceState.value

    val isAlive: Boolean
        get() = service?.asBinder()?.isBinderAlive == true

    /**
     * Executes a daemon IPC call on the IO thread pool. Wraps the response in a [Result] to handle
     * RemoteExceptions gracefully without crashing.
     */
    private suspend fun <T> runIpc(block: (ILSPManagerService) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            // Read the binder once. Reading it twice invited a TOCTOU where the daemon died
            // between the liveness check and the call, and `service!!` threw an NPE that the
            // RemoteException-only catch below let escape into the collecting coroutine.
            val binder = service
            if (binder == null || binder.asBinder()?.isBinderAlive != true) {
                return@withContext Result.failure(IllegalStateException("Daemon is not active"))
            }
            try {
                Result.success(block(binder))
            } catch (e: Exception) {
                // Deliberately broad. A SecurityException, an IllegalArgumentException, or a
                // RuntimeException thrown while unparcelling a large ParcelableListSlice are all
                // reachable here, and any of them escaping cancels the caller's scope — which in
                // a viewModelScope means the process dies.
                Result.failure(e)
            }
        }

    // --- Core Methods ---

    suspend fun getXposedApiVersion(): Result<Int> = runIpc { it.xposedApiVersion }

    suspend fun getEnabledModules(): Result<List<String>> = runIpc { it.enabledModules().toList()
    }

    /**
     * Modules the daemon could not load, though they are installed and enabled.
     *
     * The daemon keeps what the user asked for separately from what it can actually load, and the
     * two can disagree — an APK whose path will not resolve, or whose DEX will not parse. Without
     * this the module simply looked switched off, which is both wrong and unexplainable.
     */
    /**
     * Opens the app's own screen: its launcher entry, or failing that its Xposed settings activity.
     *
     * Two ways in, tried in that order, because a great many modules deliberately have no launcher
     * icon — a module is not something anyone wants in their drawer — and expose their settings
     * through the convention the original framework established instead. Looking only for a
     * launcher told the owners of those modules there was "nothing to open", which was this app not
     * knowing where to knock.
     *
     * Resolved as the module's own user throughout: the manager's package manager cannot see
     * another profile's activities.
     *
     * Returns false when neither exists, which is a real answer and not a failure.
     */
    suspend fun openAppUi(
        packageName: String,
        userId: Int,
        /**
         * True for a module, where the companion screen is the point.
         *
         * A module that declares both is declaring which is which: the Xposed category marks the
         * screen written to configure the module, the launcher entry is whatever it puts in the
         * drawer. For an ordinary app there is no such distinction and only the launcher applies.
         */
        companionFirst: Boolean = false,
    ): Result<Boolean> = runIpc { service ->
        val order =
            if (companionFirst) listOf(XPOSED_MODULE_SETTINGS_CATEGORY, Intent.CATEGORY_LAUNCHER)
            else listOf(Intent.CATEGORY_LAUNCHER, XPOSED_MODULE_SETTINGS_CATEGORY)
        val target =
            order.asSequence()
                .mapNotNull { category ->
                    val intent =
                        Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName)
                    service
                        .queryIntentActivitiesAsUser(intent, 0, userId)
                        ?.list
                        ?.firstOrNull()
                }
                .firstOrNull()
                ?: return@runIpc false

        service.startActivityAsUserWithFeature(
            Intent(Intent.ACTION_MAIN)
                .setClassName(target.activityInfo.packageName, target.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            userId,
        )
        true
    }

    suspend fun getUnloadableModules(): Result<List<String>> = runIpc {
        it.unloadableModules.toList()
    }

    /** Why a module in that list could not be loaded; `MODULE_LOAD_OK` when it is fine. */
    suspend fun getModuleLoadState(packageName: String): Result<Int> = runIpc {
        it.getModuleLoadState(packageName)
    }

    suspend fun setModuleEnabled(packageName: String, enable: Boolean): Result<Boolean> = runIpc {
        if (enable) {
            it.enableModule(packageName)
        } else {
            it.disableModule(packageName)
        }
    }

    suspend fun getFrameworkCommit(): Result<String?> = runIpc { it.frameworkCommit }

    suspend fun getXposedVersionName(): Result<String> = runIpc { it.xposedVersionName }

    suspend fun getXposedVersionCode(): Result<Long> = runIpc { it.xposedVersionCode }

    suspend fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): Result<List<android.content.pm.PackageInfo>> = runIpc { it.getInstalledPackagesFromAllUsers(flags, filterNoProcess).list
    }

    suspend fun setModuleScope(
        packageName: String,
        applications: List<org.lsposed.lspd.models.Application>,
    ): Result<Boolean> = runIpc { it.setModuleScope(packageName, applications) }

    suspend fun getModuleScope(
        packageName: String
    ): Result<List<org.lsposed.lspd.models.Application>> = runIpc { it.getModuleScope(packageName)
    }

    suspend fun enableStatusNotification(): Result<Boolean> = runIpc { it.enableStatusNotification()
    }

    suspend fun setEnableStatusNotification(enabled: Boolean): Result<Unit> = runIpc { it.setEnableStatusNotification(enabled)
    }

    suspend fun isVerboseLogEnabled(): Result<Boolean> = runIpc { it.isVerboseLog }

    suspend fun setVerboseLogEnabled(enabled: Boolean): Result<Unit> = runIpc { it.isVerboseLog = enabled
    }

    /**
     * The current log part, or `null` when the daemon has not opened one yet.
     *
     * The AIDL returns a platform type, so the previous `Result<ParcelFileDescriptor>` happily
     * carried a `null` under a non-null type parameter: `getOrNull()` could not tell "the daemon is
     * unreachable" from "there is no log file yet", and `getOrThrow()` would have handed a null back
     * as non-null. Those are two different situations and the Logs screen renders them differently,
     * so the nullability is admitted here instead of being lost.
     */
    /**
     * The rotated parts the daemon still holds for one of the two logs, oldest first.
     *
     * Empty against an older daemon that has no such call — the manager then simply shows the live
     * part, which is what it did before this existed.
     */
    suspend fun getLogParts(verbose: Boolean): Result<List<String>> = runIpc {
        it.getLogParts(verbose).orEmpty()
    }

    suspend fun getLogPart(
        verbose: Boolean,
        name: String,
    ): Result<android.os.ParcelFileDescriptor?> = runIpc { it.getLogPart(verbose, name) }

    suspend fun getLog(verbose: Boolean): Result<android.os.ParcelFileDescriptor?> = runIpc {
        if (verbose) it.verboseLog else it.modulesLog
    }

    suspend fun clearLogs(verbose: Boolean): Result<Boolean> = runIpc { it.clearLogs(verbose)
    }

    suspend fun getPackageInfo(
        packageName: String,
        flags: Int,
        userId: Int,
    ): Result<android.content.pm.PackageInfo> = runIpc { it.getPackageInfo(packageName, flags, userId)
    }

    suspend fun forceStopPackage(packageName: String, userId: Int): Result<Unit> = runIpc { it.forceStopPackage(packageName, userId)
    }

    suspend fun reboot(): Result<Unit> = runIpc { it.reboot() }

    suspend fun uninstallPackage(packageName: String, userId: Int): Result<Boolean> = runIpc { it.uninstallPackage(packageName, userId)
    }

    suspend fun isSepolicyLoaded(): Result<Boolean> = runIpc { it.isSepolicyLoaded }

    suspend fun getUsers(): Result<List<org.lsposed.lspd.models.UserInfo>> = runIpc { it.users
    }

    suspend fun installExistingPackageAsUser(packageName: String, userId: Int): Result<Boolean> =
        runIpc {
            val INSTALL_SUCCEEDED = 1
            it.installExistingPackageAsUser(packageName, userId) == INSTALL_SUCCEEDED
        }

    suspend fun systemServerRequested(): Result<Boolean> = runIpc { it.systemServerRequested()
    }

    suspend fun dex2oatFlagsLoaded(): Result<Boolean> = runIpc { it.dex2oatFlagsLoaded() }

    suspend fun getDex2OatWrapperCompatibility(): Result<Int> = runIpc {
        it.dex2OatWrapperCompatibility
    }

    suspend fun optimizePackage(packageName: String): Result<Boolean> = runIpc {
        it.optimizePackage(packageName)
    }

    /** Writes a zip of every log the daemon holds into [zipFd], for sharing a bug report. */
    suspend fun writeLogsTo(zipFd: android.os.ParcelFileDescriptor): Result<Unit> = runIpc {
        it.getLogs(zipFd)
    }

    /** Restarts the manager after a framework update, re-entering through the given intent. */
    suspend fun restartFor(intent: android.content.Intent): Result<Unit> = runIpc {
        it.restartFor(intent)
    }

    suspend fun startActivityAsUserWithFeature(
        intent: android.content.Intent,
        userId: Int,
    ): Result<Int> = runIpc { it.startActivityAsUserWithFeature(intent, userId) }

    suspend fun queryIntentActivitiesAsUser(
        intent: android.content.Intent,
        flags: Int,
        userId: Int,
    ): Result<List<android.content.pm.ResolveInfo>> = runIpc { it.queryIntentActivitiesAsUser(intent, flags, userId).list
    }

    /** Restarts the framework without rebooting the device. Everything on screen goes with it. */
    suspend fun softReboot(): Result<Unit> = runIpc { it.softReboot() }

    /** Whether apps that declare no launcher entry are given one anyway; the platform default. */
    suspend fun forcedLauncherIcons(): Result<Boolean> = runIpc { it.forcedLauncherIcons() }

    suspend fun setForcedLauncherIcons(force: Boolean): Result<Unit> = runIpc {
        it.setForcedLauncherIcons(force)
    }

    suspend fun getIncludeNewApps(packageName: String): Result<Boolean> = runIpc { it.getIncludeNewApps(packageName)
    }

    suspend fun setIncludeNewApps(packageName: String, enable: Boolean): Result<Unit> = runIpc { it.setIncludeNewApps(packageName, enable)
    }

    suspend fun getRootImplementation(): Result<Int> = runIpc { it.rootImplementation }

    suspend fun getRootImplementationVersion(): Result<String?> = runIpc {
        it.rootImplementationVersion
    }

    /**
     * Starts a flash and returns as soon as the daemon has accepted it.
     *
     * Deliberately not wrapped into a suspend-until-finished call: the result arrives on [callback]
     * over minutes, and a coroutine suspended across a reboot-inducing operation is a coroutine
     * that never resumes. The caller keeps the callback alive for as long as it wants the output.
     */
    suspend fun installFrameworkZip(
        zipPath: String,
        callback: IFrameworkInstallCallback,
    ): Result<Unit> = runIpc { it.installFrameworkZip(zipPath, callback) }
}

/**
 * How an Xposed module has advertised its settings screen since the original framework.
 *
 * A module that hides its launcher icon still needs somewhere to be configured from, and this is
 * where it says so.
 */
private const val XPOSED_MODULE_SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"
