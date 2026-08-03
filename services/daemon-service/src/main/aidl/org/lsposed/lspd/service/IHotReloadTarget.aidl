package org.lsposed.lspd.service;

import org.lsposed.lspd.models.HotReloadOutcome;
import org.lsposed.lspd.models.Module;

/**
 * Daemon-to-process entry point for hot reloading a module generation in place.
 *
 * <p>Registered once per process while the framework bootstraps, before any module is loaded, so
 * that a target exists regardless of when the daemon's module cache becomes available.</p>
 */
interface IHotReloadTarget {
    /**
     * Replaces the loaded generation of modulePackageName with newModule.
     *
     * <p>Runs the old code's onHotReloading and the new code's onHotReloaded, and blocks for their
     * duration; the daemon calls this off the binder thread that served the module app.</p>
     */
    HotReloadOutcome hotReload(String modulePackageName, in Bundle extras, in Module newModule) = 1;
}
