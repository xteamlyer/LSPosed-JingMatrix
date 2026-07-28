package org.matrix.vector.manager.demo

import org.lsposed.lspd.ILSPManagerService

/**
 * A device state the manager cannot otherwise be shown.
 *
 * Only states that need a *broken or unusual system* are here. Anything reachable by using the app
 * normally — an empty search, airplane mode, a module with nothing selected — is deliberately
 * absent: those get found the first day a build is in anyone's hands, and scripting them would be
 * upkeep with no return.
 *
 * Everything downstream of the binder runs for real against these values: the status derivation,
 * the issue list, the update logic, the install screen. This scripts what the *device* says, not
 * what the UI shows, so a bug in how the manager reacts is still a bug you can see here.
 */
data class DemoScenario(
    val id: String,
    val title: String,
    val summary: String,

    /** False binds nothing at all, which is how the framework reads as not activated. */
    val connected: Boolean = true,

    /** Delay on every status call. Non-zero is the only way to hold "Checking…" still. */
    val stallMillis: Long = 0,
    val sepolicyLoaded: Boolean = true,
    val systemServerRequested: Boolean = true,
    val dex2oatFlagsLoaded: Boolean = true,
    val dex2oatCompatibility: Int = ILSPManagerService.DEX2OAT_OK,

    /**
     * What the framework claims to implement.
     *
     * Lowering it is how a module becomes incompatible without fabricating a module: the real ones
     * on the device declare a real minimum, and the framework simply stops meeting it.
     */
    val xposedApiVersion: Int = -1,
    val xposedVersionCode: Long = -1,
    val rootImplementation: Int = ILSPManagerService.ROOT_MAGISK,
    val rootVersion: String? = "28.1",
    val install: InstallScript = InstallScript.SUCCEEDS,
) {
    /**
     * How a flash behaves when the install screen asks for one.
     *
     * Not reachable yet, and worth saying so rather than leaving a scenario that quietly does
     * nothing: the Install button is only enabled when an update actually exists, and whether one
     * exists comes from GitHub rather than from the daemon. This seam stops at the binder, so it
     * can script the flash but not the release that triggers it. Reaching these needs the HTTP
     * seam — an interceptor serving a canned release list — which is the one piece of the harness
     * still missing.
     */
    enum class InstallScript {
        SUCCEEDS,

        /**
         * Fails after output has already been streamed.
         *
         * The one worth having: a flash that fails *before* it starts is a message, but a flash
         * that dies halfway leaves the module tree in whatever state the installer reached, and
         * that is the case the screen has to report usefully.
         */
        FAILS_PARTWAY,

        /** Refused outright, with no output. */
        NO_ROOT,
    }

    companion object {
        /** -1 means "whatever the real daemon says", so a scenario only lies where it means to. */
        const val PASS_THROUGH = -1
    }
}

/**
 * The menu.
 *
 * Ordered by how hard the state is to reach honestly, not by severity: the ones at the top cannot
 * be produced on a working phone at all.
 */
val DEMO_SCENARIOS: List<DemoScenario> =
    listOf(
        DemoScenario(
            id = "healthy",
            title = "Healthy",
            summary = "Pass everything through to the real daemon. The way back.",
        ),
        DemoScenario(
            id = "sepolicy",
            title = "SELinux policy not loaded",
            summary = "Degraded, one cause. Needs a root implementation that skipped our rules.",
            sepolicyLoaded = false,
        ),
        DemoScenario(
            id = "system-server",
            title = "System framework injection failed",
            summary = "Degraded, one cause. Normally needs another root module interfering.",
            systemServerRequested = false,
        ),
        DemoScenario(
            id = "dex2oat",
            title = "Dex optimizer wrapper unavailable",
            summary = "Degraded, one cause. Needs system properties removed or changed.",
            dex2oatFlagsLoaded = false,
            dex2oatCompatibility = ILSPManagerService.DEX2OAT_MOUNT_FAILED,
        ),
        DemoScenario(
            id = "all-issues",
            title = "All three causes at once",
            summary = "Whether the issue list reads as a list or as a wall.",
            sepolicyLoaded = false,
            systemServerRequested = false,
            dex2oatFlagsLoaded = false,
            dex2oatCompatibility = ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT,
        ),
        DemoScenario(
            id = "inactive",
            title = "Framework not activated",
            summary = "No daemon at all. Every screen that needs one has to say so.",
            connected = false,
        ),
        DemoScenario(
            id = "checking",
            title = "Checking, held still",
            summary = "The transient state on arrival, stalled for eight seconds.",
            stallMillis = 8_000,
        ),
        DemoScenario(
            id = "api-too-old",
            title = "Framework below what modules need",
            summary = "API 82. Installed modules that need more become incompatible.",
            xposedApiVersion = 82,
        ),
        DemoScenario(
            id = "root-none",
            title = "No root implementation",
            summary = "Nothing to flash through. The install path must refuse, not fail.",
            rootImplementation = ILSPManagerService.ROOT_NONE,
            rootVersion = null,
            install = DemoScenario.InstallScript.NO_ROOT,
        ),
        DemoScenario(
            id = "root-multiple",
            title = "Two root implementations fighting",
            summary = "Flashing through either would be a guess, and must be named as such.",
            rootImplementation = ILSPManagerService.ROOT_MULTIPLE,
            rootVersion = null,
            install = DemoScenario.InstallScript.NO_ROOT,
        ),
        DemoScenario(
            id = "root-too-old",
            title = "Root implementation too old",
            summary = "Installed but not usable. Distinct from having none.",
            rootImplementation = ILSPManagerService.ROOT_TOO_OLD,
            rootVersion = "20.4",
            install = DemoScenario.InstallScript.NO_ROOT,
        ),
        DemoScenario(
            id = "root-ksu",
            title = "KernelSU",
            summary = "The install path quotes the implementation it found.",
            rootImplementation = ILSPManagerService.ROOT_KERNELSU,
            rootVersion = "12045",
        ),
        DemoScenario(
            id = "root-apatch",
            title = "APatch",
            summary = "As above, third implementation.",
            rootImplementation = ILSPManagerService.ROOT_APATCH,
            rootVersion = "10763",
        ),
        DemoScenario(
            id = "install-fails",
            title = "Flash that dies halfway",
            summary = "Output already streamed, then a non-zero exit. The case that bites.",
            install = DemoScenario.InstallScript.FAILS_PARTWAY,
        ),
    )
