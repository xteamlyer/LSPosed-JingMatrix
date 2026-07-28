package org.matrix.vector.manager.demo

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.ParcelFileDescriptor
import org.lsposed.lspd.IFrameworkInstallCallback
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.UserInfo
import rikka.parcelablelist.ParcelableListSlice

/**
 * The daemon, as a script.
 *
 * The fake sits at the *binder*, which is the boundary between this app and the privileged system,
 * and is the reason a demo mode is worth having at all: everything from here inwards — DaemonClient,
 * the repositories, the view models, the status derivation that turns three booleans into an issue
 * list — runs exactly as it does in production. What is faked is what the device says about itself,
 * not what the manager concludes. A bug in the concluding is still visible.
 *
 * Two other seams were considered and rejected. Faking a repository would have meant the status
 * derivation never ran, which is the code most likely to be wrong. Faking a view model would have
 * meant testing the screen against a pipeline that was not there — and the bugs this week lived in
 * the pipeline, not the screen.
 *
 * Anything the scenario does not have an opinion about is delegated to the real daemon when one is
 * connected, so the module list, the app list and the logs stay real while the framework's health
 * is a lie. With no daemon present the delegating calls return empties rather than throwing, so the
 * demo is still usable on a device with no Vector installed.
 *
 * Subclassing `Stub()` rather than proxying the interface is deliberate on both counts: DaemonClient
 * checks `asBinder().isBinderAlive`, which only a real Binder answers — and a new AIDL method breaks
 * this file's compilation, which is the point. A fake that silently kept working while the daemon
 * grew a new question would quietly stop covering it.
 */
class FakeManagerService(
    private val scenario: DemoScenario,
    private val real: ILSPManagerService?,
) : ILSPManagerService.Stub() {

    private fun stall() {
        if (scenario.stallMillis > 0) Thread.sleep(scenario.stallMillis)
    }

    // ---- what the scenario exists to lie about ------------------------------------------------

    override fun isSepolicyLoaded(): Boolean {
        stall()
        return scenario.sepolicyLoaded
    }

    override fun systemServerRequested(): Boolean {
        stall()
        return scenario.systemServerRequested
    }

    override fun dex2oatFlagsLoaded(): Boolean {
        stall()
        return scenario.dex2oatFlagsLoaded
    }

    override fun getDex2OatWrapperCompatibility(): Int = scenario.dex2oatCompatibility

    override fun getXposedApiVersion(): Int =
        scenario.xposedApiVersion.takeIf { it != DemoScenario.PASS_THROUGH }
            ?: real?.xposedApiVersion
            ?: 0

    override fun getXposedVersionCode(): Long =
        scenario.xposedVersionCode.takeIf { it != DemoScenario.PASS_THROUGH.toLong() }
            ?: real?.xposedVersionCode
            ?: 0L

    override fun getRootImplementation(): Int = scenario.rootImplementation

    override fun getRootImplementationVersion(): String? = scenario.rootVersion

    /**
     * A flash, without a flash.
     *
     * Emits on its own thread and never blocks the caller, because the real one does not either —
     * a screen that only works when the lines arrive on the binder thread would pass here and hang
     * on a device.
     */
    override fun installFrameworkZip(zipPath: String?, callback: IFrameworkInstallCallback?) {
        if (callback == null) return
        Thread {
                fun say(line: String) {
                    runCatching { callback.onLine(line) }
                    Thread.sleep(220)
                }
                when (scenario.install) {
                    DemoScenario.InstallScript.NO_ROOT -> {
                        runCatching { callback.onFinished(ILSPManagerService.INSTALL_NO_ROOT) }
                    }
                    DemoScenario.InstallScript.SUCCEEDS -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("- Setting permissions")
                        say("- Done. Reboot to apply.")
                        runCatching { callback.onFinished(0) }
                    }
                    DemoScenario.InstallScript.FAILS_PARTWAY -> {
                        say("- Target: $zipPath")
                        say("- Extracting module files")
                        say("- Device is arm64-v8a API 36")
                        say("- Installing Vector")
                        say("! Failed to copy zygisk binary: No space left on device")
                        runCatching { callback.onFinished(1) }
                    }
                }
            }
            .start()
    }

    // ---- everything else is the real device, when there is one ---------------------------------

    override fun getInstalledPackagesFromAllUsers(
        flags: Int,
        filterNoProcess: Boolean,
    ): ParcelableListSlice<PackageInfo> =
        real?.getInstalledPackagesFromAllUsers(flags, filterNoProcess)
            ?: ParcelableListSlice(emptyList())

    override fun enabledModules(): Array<String> = real?.enabledModules() ?: emptyArray()

    override fun enableModule(packageName: String?): Boolean =
        real?.enableModule(packageName) ?: false

    override fun disableModule(packageName: String?): Boolean =
        real?.disableModule(packageName) ?: false

    override fun setModuleScope(packageName: String?, scope: MutableList<Application>?): Boolean =
        real?.setModuleScope(packageName, scope) ?: false

    override fun getModuleScope(packageName: String?): MutableList<Application> =
        real?.getModuleScope(packageName) ?: mutableListOf()

    override fun isVerboseLog(): Boolean = real?.isVerboseLog ?: false

    override fun setVerboseLog(enabled: Boolean) {
        real?.setVerboseLog(enabled)
    }

    override fun getVerboseLog(): ParcelFileDescriptor? = real?.verboseLog

    override fun getModulesLog(): ParcelFileDescriptor? = real?.modulesLog

    override fun getLogParts(verbose: Boolean): MutableList<String> =
        real?.getLogParts(verbose) ?: mutableListOf()

    override fun getLogPart(verbose: Boolean, name: String?): ParcelFileDescriptor? =
        real?.getLogPart(verbose, name)

    override fun getXposedVersionName(): String? = real?.xposedVersionName

    override fun clearLogs(verbose: Boolean): Boolean = real?.clearLogs(verbose) ?: false

    override fun getPackageInfo(packageName: String?, flags: Int, uid: Int): PackageInfo? =
        real?.getPackageInfo(packageName, flags, uid)

    override fun forceStopPackage(packageName: String?, userId: Int) {
        real?.forceStopPackage(packageName, userId)
    }

    /** Not delegated. A demo build must not be able to reboot the device by accident. */
    override fun reboot() = Unit

    override fun uninstallPackage(packageName: String?, userId: Int): Boolean =
        real?.uninstallPackage(packageName, userId) ?: false

    override fun getUsers(): MutableList<UserInfo> = real?.users ?: mutableListOf()

    override fun installExistingPackageAsUser(packageName: String?, userId: Int): Int =
        real?.installExistingPackageAsUser(packageName, userId) ?: 0

    override fun startActivityAsUserWithFeature(intent: Intent?, userId: Int): Int =
        real?.startActivityAsUserWithFeature(intent, userId) ?: 0

    override fun queryIntentActivitiesAsUser(
        intent: Intent?,
        flags: Int,
        userId: Int,
    ): ParcelableListSlice<ResolveInfo> =
        real?.queryIntentActivitiesAsUser(intent, flags, userId) ?: ParcelableListSlice(emptyList())

    override fun setHiddenIcon(hide: Boolean) {
        real?.setHiddenIcon(hide)
    }

    override fun getLogs(zipFd: ParcelFileDescriptor?) {
        real?.getLogs(zipFd)
    }

    override fun restartFor(intent: Intent?) {
        real?.restartFor(intent)
    }

    override fun optimizePackage(packageName: String?): Boolean =
        real?.optimizePackage(packageName) ?: false

    override fun enableStatusNotification(): Boolean = real?.enableStatusNotification() ?: false

    override fun setEnableStatusNotification(enable: Boolean) {
        real?.setEnableStatusNotification(enable)
    }

    override fun getAutoInclude(packageName: String?): Boolean =
        real?.getAutoInclude(packageName) ?: false

    override fun setAutoInclude(packageName: String?, enable: Boolean): Boolean =
        real?.setAutoInclude(packageName, enable) ?: false
}
