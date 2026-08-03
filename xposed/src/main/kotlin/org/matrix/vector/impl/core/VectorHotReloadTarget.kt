package org.matrix.vector.impl.core

import android.os.Bundle
import org.lsposed.lspd.models.HotReloadOutcome
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget

/** Registered once while the framework bootstraps, before any module is loaded. */
object VectorHotReloadTarget : IHotReloadTarget.Stub() {

    override fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        newModule: Module?,
    ): HotReloadOutcome = VectorModuleManager.hotReload(modulePackageName, extras, newModule)
}
