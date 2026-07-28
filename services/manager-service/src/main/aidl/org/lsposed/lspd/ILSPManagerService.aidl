package org.lsposed.lspd;

import rikka.parcelablelist.ParcelableListSlice;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.IFrameworkInstallCallback;


interface ILSPManagerService {
    const int DEX2OAT_OK = 0;
    const int DEX2OAT_CRASHED = 1;
    const int DEX2OAT_MOUNT_FAILED = 2;
    const int DEX2OAT_SELINUX_PERMISSIVE = 3;
    const int DEX2OAT_SEPOLICY_INCORRECT = 4;

    /**
     * Which root implementation is managing this device.
     *
     * The three failure values are kept apart rather than collapsed into one "unsupported",
     * because they need three different sentences from the manager: nothing is installed, what is
     * installed is too old to flash through, or two implementations are fighting and flashing
     * through either would be a guess. NeoZygisk draws exactly these distinctions, and the manager
     * is reporting on the same device state.
     */
    const int ROOT_NONE = 0;
    const int ROOT_TOO_OLD = 1;
    const int ROOT_MULTIPLE = 2;
    const int ROOT_MAGISK = 3;
    const int ROOT_KERNELSU = 4;
    const int ROOT_APATCH = 5;

    /** Nothing was flashed: no usable root implementation. Distinct from any installer exit code. */
    const int INSTALL_NO_ROOT = -1;
    /** The installer binary could not be started at all. */
    const int INSTALL_NOT_EXECUTED = -2;
    /** The zip named by the manager does not exist or is not readable by the daemon. */
    const int INSTALL_NO_SUCH_FILE = -3;

    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) = 2;

    String[] enabledModules() = 3;

    boolean enableModule(String packageName) = 4;

    boolean disableModule(String packageName) = 5;

    boolean setModuleScope(String packageName, in List<Application> scope) = 6;

    List<Application> getModuleScope(String packageName) = 7;

    boolean isVerboseLog() = 11;

    void setVerboseLog(boolean enabled) = 12;

    ParcelFileDescriptor getVerboseLog() = 16;

    ParcelFileDescriptor getModulesLog() = 17;

    /**
     * The rotated log parts the daemon still holds, oldest first, as bare file names.
     *
     * getVerboseLog()/getModulesLog() only ever hand over the part being written. The daemon keeps
     * ten, so on a device that has been logging for an hour most of the history was unreachable.
     */
    List<String> getLogParts(boolean verbose) = 53;

    /** Opens one part by the name getLogParts() returned. Any other name is refused. */
    ParcelFileDescriptor getLogPart(boolean verbose, String name) = 54;

    long getXposedVersionCode() = 18;

    String getXposedVersionName() = 19;

    int getXposedApiVersion() = 20;

    boolean clearLogs(boolean verbose) = 21;

    PackageInfo getPackageInfo(String packageName, int flags, int uid) = 22;

    void forceStopPackage(String packageName, int userId) = 23;

    void reboot() = 24;

    boolean uninstallPackage(String packageName, int userId) = 25;

    boolean isSepolicyLoaded() = 26;

    List<UserInfo> getUsers() = 27;

    int installExistingPackageAsUser(String packageName, int userId) = 28;

    boolean systemServerRequested() = 29;

    int startActivityAsUserWithFeature(in Intent intent,  int userId) = 30;

    ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(in Intent intent, int flags, int userId) = 31;

    boolean dex2oatFlagsLoaded() = 32;

    void setHiddenIcon(boolean hide) = 33;

    void getLogs(in ParcelFileDescriptor zipFd) = 34;

    void restartFor(in Intent intent) = 35;

    boolean optimizePackage(String packageName) = 40;

    int getDex2OatWrapperCompatibility() = 44;

    boolean enableStatusNotification() = 47;

    void setEnableStatusNotification(boolean enable) = 48;

    boolean getAutoInclude(String packageName) = 51;

    boolean setAutoInclude(String packageName, boolean enable) = 52;

    /** One of the ROOT_* constants. Detected once and cached, as the detection shells out. */
    int getRootImplementation() = 55;

    /** What the root implementation calls itself, for the manager to quote. Null when unknown. */
    String getRootImplementationVersion() = 56;

    /**
     * Flashes a module zip through whatever root implementation is managing the device.
     *
     * The daemon already runs as root, so this execs the installer directly rather than going
     * through `su` — the same commands the project's own gradle install tasks use. Output is
     * streamed to [callback] *and* written to the daemon's log, so a flash that failed on a device
     * that is now unbootable can still be read out of a saved bug report.
     *
     * Returns immediately; the work runs on a daemon thread and reports through [callback].
     */
    void installFrameworkZip(String zipPath, IFrameworkInstallCallback callback) = 57;
}
