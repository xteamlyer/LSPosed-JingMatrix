package org.lsposed.lspd.service;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadOutcomeCallback;

/**
 * Daemon-to-process entry point for hot reloading a module generation in place.
 *
 * <p>Registered once per process while the framework bootstraps, before any module is loaded, so
 * that a target exists regardless of when the daemon's module cache becomes available.</p>
 */
interface IHotReloadTarget {
    /**
     * Replaces the loaded generation of modulePackageName with newModule, and answers through
     * callback.
     *
     * <p>oneway, and answered out of band, because the work runs the old code's onHotReloading and
     * the new code's onHotReloaded - arbitrary module code with no bound on how long it takes. A
     * synchronous form would pin a daemon thread for that whole time and, worse, leave the target
     * stuck in RELOADING for good if the module never returned, since binder itself has no
     * timeout. The daemon supplies one instead.</p>
     */
    oneway void hotReload(String modulePackageName, in Bundle extras, in Module newModule,
                          IHotReloadOutcomeCallback callback) = 1;
}
