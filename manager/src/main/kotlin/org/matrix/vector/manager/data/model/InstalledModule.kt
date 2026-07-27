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
)
