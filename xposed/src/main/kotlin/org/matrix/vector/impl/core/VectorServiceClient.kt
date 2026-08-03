package org.matrix.vector.impl.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.IFrameworkService
import org.lsposed.lspd.util.Utils.Log

/**
 * Singleton client for managing IPC communication with the injected manager service. Handles Binder
 * death gracefully and ensures safe remote execution.
 */
object VectorServiceClient : IFrameworkService, IBinder.DeathRecipient {

    private const val TAG = "VectorServiceClient"

    private var service: IFrameworkService? = null
    var processName: String = ""
        private set

    @Synchronized
    fun init(appService: IFrameworkService?, niceName: String) {
        val binder = appService?.asBinder()
        if (service == null && binder != null) {
            runCatching {
                    service = appService
                    processName = niceName
                    binder.linkToDeath(this, 0)
                }
                .onFailure {
                    Log.e(TAG, "Failed to link to death for service in process: $niceName", it)
                    service = null
                }

            // Registered here rather than after module loading: system_server loads its modules
            // before the daemon's module cache exists, and it has to be a reloadable target too.
            service?.let {
                try {
                    it.attachProcessChannel(VectorProcessChannel)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register the hot reload target in process: $niceName", t)
                }
            }
        }
    }

    override fun attachProcessChannel(target: IProcessChannel?) {
        try {
            service?.attachProcessChannel(target)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register a hot reload target", t)
        }
    }

    override fun isLogMuted(): Boolean {
        return runCatching { service?.isLogMuted == true }.getOrDefault(false)
    }

    override fun getLegacyModulesList(): List<LoadedModule> {
        return runCatching { service?.legacyModulesList }.getOrNull() ?: emptyList()
    }

    override fun getModulesList(): List<LoadedModule> {
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

    override fun binderDied() {
        service?.asBinder()?.unlinkToDeath(this, 0)
        service = null
    }
}
