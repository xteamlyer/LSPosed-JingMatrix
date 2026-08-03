package org.matrix.vector.ipc;

import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver;

/**
 * The one thing the daemon calls <i>into</i> an injected process for.
 *
 * <p>One of two interfaces that point this way - {@link IRemotePreferenceCallback} is the other,
 * and it carries nothing but preference diffs. This one drives a module's lifecycle, so it is
 * deliberately not a general control surface: a hooked process runs as the app, and anything
 * broader here would be reachable by the app itself. The process side additionally checks that the
 * caller is the daemon.</p>
 *
 * <p>Handed to the daemon by {@link IFrameworkService#attachProcessChannel} while the framework
 * bootstraps, before any module has loaded and carrying no module identity at all - which is what
 * makes it work for system_server, whose modules load before the daemon's module cache exists.</p>
 */
interface IProcessChannel {
    /**
     * Loads a new generation of {@code module} over the one already running, and answers through
     * {@code receiver}.
     *
     * <p>oneway, and answered out of band, because this runs the old code's
     * {@code onHotReloading} and the new code's {@code onHotReloaded} - module code, with no bound
     * on how long it takes. A synchronous form would pin a daemon thread for that whole time and,
     * worse, leave the target stuck in RELOADING for good if the module never returned, since
     * binder has no timeout of its own. The daemon supplies one instead.</p>
     *
     * @param modulePackageName which loaded module to replace
     * @param extras            what the module app passed to {@code hotReloadModule}, reaching the
     *                          old code as {@code HotReloadingParam#getExtras}. Null for a reload
     *                          the daemon started itself, on autoHotReload
     * @param module            the generation to load
     */
    oneway void hotReload(String modulePackageName, in Bundle extras, in LoadedModule module,
                          IHotReloadOutcomeReceiver receiver) = 1;
}
