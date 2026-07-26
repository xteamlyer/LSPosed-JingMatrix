package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadTarget;

interface ILSPApplicationService {
    /**
     * Registers this process's hot reload entry point. Called once while the framework bootstraps,
     * independently of module loading, so that system_server - whose modules are loaded before the
     * daemon's module cache exists - is a reloadable target like any other process.
     */
    void registerHotReloadTarget(IHotReloadTarget target);

    boolean isLogMuted();

    List<Module> getLegacyModulesList();

    List<Module> getModulesList();

    String getPrefsPath(String packageName);

    ParcelFileDescriptor requestInjectedManagerBinder(out List<IBinder> binder);
}
