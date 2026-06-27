package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadTarget;

interface ILSPApplicationService {
    boolean isLogMuted();

    List<Module> getLegacyModulesList();

    List<Module> getModulesList();

    String getPrefsPath(String packageName);

    ParcelFileDescriptor requestInjectedManagerBinder(out List<IBinder> binder);

    long registerHotReloadTarget(String modulePackageName, long loadedVersionCode, IHotReloadTarget target);
}
