package org.matrix.vector.manager.data.model

import android.content.pm.ApplicationInfo

/** Represents an installed application for the Scope configuration screen. */
data class AppInfo(
    val packageName: String,
    val userId: Int,
    val appName: String,
    val isSystemApp: Boolean,
    val isGame: Boolean,
    val isSelectedInScope: Boolean,
    val isRecommended: Boolean,
    /** When the package was last installed or updated, for the "recently updated" sort. */
    val lastUpdateTime: Long,
    /** When it was first installed — a different question, and the legacy UI sorted on both. */
    val firstInstallTime: Long,
    val applicationInfo: ApplicationInfo,
)
