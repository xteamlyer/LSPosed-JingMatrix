package org.matrix.vector.ipc;

import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IProcessChannel;

/**
 * What an injected process asks the framework for, once the zygisk handshake has given it one.
 *
 * <p>The daemon authenticates every call here by {@code Binder.getCallingUid()/getCallingPid()}
 * against the process registry the handshake built, so a caller can only ever act as itself.</p>
 *
 * <p><b>Transaction ids are implicit in this file.</b> A new method must be appended; inserting one
 * anywhere above renumbers every method after it, and the daemon and the injected processes are
 * only guaranteed to agree because they ship in the same zip.</p>
 */
interface IFrameworkService {
    /** Whether the user has asked the framework to keep quiet in the log. */
    boolean isLogMuted();

    /** The legacy (de.robv) modules in scope for this process. */
    List<LoadedModule> getLegacyModules();

    /**
     * The libxposed modules in scope for this process.
     *
     * <p>Answering this is also what makes the calling process a hot reload target for each module
     * returned: the daemon knows it served module M to process P, which is the whole of what
     * {@code getRunningTargets()} needs, so nothing has to be reported back afterwards.</p>
     *
     * <p>That is deliberate rather than incidental. Deriving targets from a registration call made
     * by the injected process is what made system_server unreachable in the first attempt at hot
     * reload: system_server loads its modules before the daemon's module cache exists, so any
     * registration that had to look a module up there failed and was swallowed.</p>
     */
    List<LoadedModule> getModules();

    /** Where this process should look for a module's XSharedPreferences files. */
    String getPrefsPath(String packageName);

    /**
     * The manager APK, opened read-only once its signature has been verified against the one this
     * framework was built with. Null when it is missing or does not verify.
     */
    @nullable ParcelFileDescriptor openManagerApk();

    /**
     * The manager's service binder, if this process is the one that should host the manager.
     *
     * <p>Null for every other process, which is nearly all of them. Asking is how a process finds
     * out, and asking is not free of consequence: the daemon decides <i>here</i> that this process
     * is the host, so a process that asks and then does not go on to host the manager has taken
     * the slot from whichever one would have.</p>
     */
    @nullable IBinder requestManagerService();

    /**
     * Hands the daemon the channel it needs to call back into this process.
     *
     * <p>Called once while the framework bootstraps, before any module is loaded, and carrying no
     * module identity at all - so it cannot depend on the daemon's module cache being populated.
     * That dependency is exactly what stopped system_server becoming a hot reload target before.
     * The daemon files the channel against the (uid, pid) it authenticated, so a process can only
     * ever attach its own.</p>
     */
    oneway void attachProcessChannel(IProcessChannel channel);
}
