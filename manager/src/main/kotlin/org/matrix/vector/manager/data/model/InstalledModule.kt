package org.matrix.vector.manager.data.model

import android.content.pm.ApplicationInfo

/** Pure Kotlin data class representing an installed Xposed module. */
data class InstalledModule(
    val packageName: String,
    val userId: Int,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val description: String,
    val minVersion: Int,
    val targetVersion: Int,
    val isLegacy: Boolean,
    /** False when the module declares no Xposed API at all, which the old manager flagged. */
    val declaresApiVersion: Boolean,
    /** When the module was last installed or updated — how you find the one you just added. */
    val lastUpdateTime: Long,
    val isEnabled: Boolean,
    val applicationInfo: ApplicationInfo, // Kept for Icon loading via Coil/Glide later
) {
    /**
     * The API version this module *is*, as opposed to the one it asks for.
     *
     * These are two different numbers and the screen was showing the wrong one. `module.prop`
     * carries both `minApiVersion` — the author's stated floor — and `targetApiVersion`, what the
     * module was built against; the WeType module declares 101 and 102 respectively, and the badge
     * said 101.
     *
     * `targetApiVersion` is the one that decides anything. `FileSystem.readModuleInfo` picks the
     * loading strategy from it alone — `targetApi >= 101` is MODERN, `targetApi == 100` is
     * refused, and anything else falls back to a legacy `assets/xposed_init` or does not load at
     * all. `minApiVersion` is read *nowhere* in the daemon or the framework: zero occurrences.
     * Showing it as "API n" therefore reported a number the framework ignores.
     *
     * Legacy modules keep their own number, because there is no target on that scale — it comes
     * from the `xposedminversion` manifest entry and is all they have.
     */
    val apiVersion: Int =
        when {
            isLegacy -> minVersion
            targetVersion > 0 -> targetVersion
            else -> minVersion
        }
}
