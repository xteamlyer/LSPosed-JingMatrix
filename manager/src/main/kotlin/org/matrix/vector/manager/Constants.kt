package org.matrix.vector.manager

import android.os.IBinder
import kotlin.system.exitProcess
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.manager.di.ServiceLocator

/**
 * The one entry point the framework reaches by reflection.
 *
 * `ParasiticManagerHooker.sendBinderToManager` loads `<managerPackage>.Constants` out of the
 * injected dex and invokes the static `setBinder(IBinder)` below. Nothing inside this APK calls it,
 * so R8 must be told to keep both — see `proguard-rules.pro`. Renaming this class or the method
 * breaks the handshake silently, at runtime, with no compile error anywhere.
 */
object Constants {
    const val TAG = "VectorManager"

    @JvmStatic
    fun setBinder(binder: IBinder): Boolean {
        ServiceLocator.bind(ILSPManagerService.Stub.asInterface(binder))

        try {
            // If the daemon dies the manager is holding a dead binder and every screen would
            // silently show empty state, which reads as "you have no modules" rather than "the
            // framework is gone". Exiting is blunt but honest.
            binder.linkToDeath({ exitProcess(0) }, 0)
        } catch (_: Exception) {
            exitProcess(0)
        }

        return binder.isBinderAlive
    }
}
