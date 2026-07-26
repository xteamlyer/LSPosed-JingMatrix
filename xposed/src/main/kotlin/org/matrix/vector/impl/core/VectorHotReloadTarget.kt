package org.matrix.vector.impl.core

import android.os.Bundle
import org.lsposed.lspd.models.HotReloadOutcome
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget

/**
 * The process's hot reload entry point. Registered once while the framework bootstraps, before any
 * module is loaded, so the daemon has a target regardless of when modules arrive.
 *
 * The call blocks for the whole reload cycle; the daemon calls it off the binder thread that served
 * the module app.
 */
object VectorHotReloadTarget : IHotReloadTarget.Stub() {

    override fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        newModule: Module?,
    ): HotReloadOutcome = VectorModuleManager.hotReload(modulePackageName, extras, newModule)
}
