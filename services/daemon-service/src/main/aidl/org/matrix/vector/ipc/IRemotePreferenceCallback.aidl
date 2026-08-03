package org.matrix.vector.ipc;

/**
 * How the daemon tells an injected process that a module's remote preferences changed.
 *
 * <p>Registered per (module, group, user) through {@link IModuleService#requestRemotePreferences}.
 * Without it a hooked process would keep serving values the module app has already replaced, until
 * the process restarts.</p>
 */
interface IRemotePreferenceCallback {
    /** @param map the diff, in the shape RemotePreferences.Editor writes: put / delete / clear */
    oneway void onUpdate(in Bundle map);
}
