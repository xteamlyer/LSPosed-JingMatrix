package org.matrix.vector.manager.ipc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.lsposed.lspd.IFrameworkInstallCallback
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

    suspend fun setHiddenIcon(hide: Boolean): Result<Unit> = runIpc { it.setHiddenIcon(hide)
    }

    suspend fun getAutoInclude(packageName: String): Result<Boolean> = runIpc { it.getAutoInclude(packageName)
    }

    suspend fun setAutoInclude(packageName: String, enable: Boolean): Result<Unit> = runIpc { it.setAutoInclude(packageName, enable)
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
