package org.matrix.vector.impl.core

import android.os.Bundle
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget

internal object VectorHotReloadTarget : IHotReloadTarget.Stub() {
    override fun hotReloadModule(module: Module, extras: Bundle?) {
        VectorModuleManager.hotReloadModule(module, extras)
    }
}
