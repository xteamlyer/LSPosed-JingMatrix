package org.matrix.vector.ipc;

/**
 * How the daemon tells an injected process that a module's remote preferences changed.
 *
 * <p>Registered per (module, group, user) through {@link IModuleService#requestRemotePreferences}.
 * Without it a hooked process would keep serving values the module app has already replaced, until
 * the process restarts.</p>
 */
interface IRemotePreferenceCallback {
    /**
     * @param diff what changed, in the shape RemotePreferences.Editor writes it: a "put" map, a
     *             "delete" set, and a "clear" flag - not the whole group
     */
    oneway void onRemotePreferencesChanged(in Bundle diff);
}
