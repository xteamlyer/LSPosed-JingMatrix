package org.matrix.vector.ipc;

import org.matrix.vector.ipc.IRemotePreferenceCallback;

/**
 * A module's own service, as seen from inside a process the module was injected into.
 *
 * <p>Not to be confused with {@code io.github.libxposed.service.IXposedService}, which is the same
 * module's service as seen from its <b>app</b>. The two deliberately differ: an app may write its
 * remote files, a hooked process may only read them, because a hooked process runs as the app it
 * was injected into rather than as the module.</p>
 */
interface IModuleService {
    /** The framework capability bits, as {@code XposedInterface#getFrameworkProperties}. */
    long getFrameworkProperties();

    /**
     * Reads a preference group, and optionally subscribes to changes made by the module app.
     *
     * @param callback null to read once without subscribing
     */
    Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback);

    /**
     * Opens one of the module's remote files <b>read-only</b>, or null when it does not exist or
     * the path is refused - which is what lets the caller raise the FileNotFoundException the API
     * documents for both cases.
     */
    @nullable ParcelFileDescriptor openRemoteFile(String path);

    /** Names of the module's remote files. */
    String[] getRemoteFileNames();
}
