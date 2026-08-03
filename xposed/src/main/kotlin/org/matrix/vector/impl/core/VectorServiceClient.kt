package org.matrix.vector.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.util.Utils.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object VectorServiceClient : ILSPApplicationService, IBinder.DeathRecipient {

    private const val TAG = "VectorServiceClient"

    private var service: ILSPApplicationService? = null
    // Keep the binder used for linkToDeath. `service` may later be replaced by a local filtering
    // proxy, so resolving it again in binderDied() would unlink the wrong binder.
    private var linkedBinder: IBinder? = null
    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: ILSPApplicationService?, niceName: String) {
        val binder = appService?.asBinder()
        if (service == null && binder != null) {
            runCatching {
                    service = appService
                    processName = niceName
                    binder.linkToDeath(this, 0)
                    linkedBinder = binder
                }
                .onFailure {
                    Log.e(TAG, "Failed to link to death for service in process: $niceName", it)
                    service = null
                    linkedBinder = null
                }

            // Registered here rather than after module loading: system_server loads its modules
            // before the daemon's module cache exists, and it has to be a reloadable target too.
            service?.let {
                try {
                    it.registerHotReloadTarget(VectorHotReloadTarget)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register the hot reload target in process: $niceName", t)
                }
            }
        }
    }

    override fun registerHotReloadTarget(target: IHotReloadTarget?) {
        try {
            service?.registerHotReloadTarget(target)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register a hot reload target", t)
        }
    }

    override fun isLogMuted(): Boolean {
        return runCatching { service?.isLogMuted == true }.getOrDefault(false)
    }

    override fun getLegacyModulesList(): List<Module> {
        return runCatching { service?.legacyModulesList }.getOrNull() ?: emptyList()
    }

    override fun getModulesList(): List<Module> {
        return runCatching { service?.modulesList }.getOrNull() ?: emptyList()
    }

    override fun getPrefsPath(packageName: String): String? {
        return runCatching { service?.getPrefsPath(packageName) }.getOrNull()
    }

    override fun requestInjectedManagerBinder(binder: List<IBinder>): ParcelFileDescriptor? {
        return runCatching { service?.requestInjectedManagerBinder(binder) }.getOrNull()
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }

    @Synchronized
    override fun binderDied() {
        linkedBinder?.unlinkToDeath(this, 0)
        linkedBinder = null
        service = null
    }
}
