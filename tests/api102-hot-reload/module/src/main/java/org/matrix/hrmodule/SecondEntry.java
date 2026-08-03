package org.matrix.hrmodule;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * A second Java entry class, packaged only when the module is built with {@code -PhrEntries=2}.
 *
 * <p>Hot reload is specified for modules that declare <b>exactly one</b> Java entry class, so a
 * build carrying this one must answer {@code UNSUPPORTED} rather than reloading, and must say so
 * without ever running module code. It also gives {@code detach()} something to be per-entry
 * about: this entry retires itself immediately and the other one carries on.</p>
 */
public class SecondEntry extends XposedModule {

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        ModuleMain.log("SecondEntry loaded in " + param.getProcessName() + "; detaching");
        detach();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // Must never be reached: this entry detached during onModuleLoaded.
        ModuleMain.log("BUG: SecondEntry still got onPackageLoaded for " + param.getPackageName());
    }
}
