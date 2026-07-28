package org.lsposed.lspd;

/**
 * Progress of a framework flash, one line at a time.
 *
 * `oneway` throughout: the daemon must never block on the manager while an installer is running.
 * The manager is a UI process that can be killed, paused or simply slow, and a flash that stalls
 * because nobody read a line would be a flash abandoned halfway through — with the module tree in
 * whatever state the installer had reached.
 */
oneway interface IFrameworkInstallCallback {

    /** One line of the installer's combined stdout and stderr, without its trailing newline. */
    void onLine(String line);

    /**
     * The installer exited.
     *
     * [exitCode] is the process's own status, or a negative value when it could not be started at
     * all — see ILSPManagerService.INSTALL_* for those.
     */
    void onFinished(int exitCode);
}
