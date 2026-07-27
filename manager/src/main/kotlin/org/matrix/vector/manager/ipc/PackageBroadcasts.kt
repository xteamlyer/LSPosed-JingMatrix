package org.matrix.vector.manager.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed class PackageEvent {
    data class Added(val packageName: String, val userId: Int) : PackageEvent()

    data class Removed(val packageName: String, val userId: Int, val fullyRemoved: Boolean) :
        PackageEvent()

    data class Changed(val packageName: String, val userId: Int) : PackageEvent()
}

/** Provides a continuous stream of package events (installs, removals, updates). */
fun Context.packageEventsFlow(): Flow<PackageEvent> = callbackFlow {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val userId = intent.getIntExtra(Intent.EXTRA_USER, 0)

                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        trySend(PackageEvent.Added(packageName, userId))
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val fullyRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)
                        trySend(PackageEvent.Removed(packageName, userId, fullyRemoved))
                    }
                    Intent.ACTION_PACKAGE_CHANGED -> {
                        trySend(PackageEvent.Changed(packageName, userId))
                    }
                }
            }
        }

    val filter =
        IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

    registerReceiver(receiver, filter)

    // When the flow collection is cancelled (e.g. app goes to background/dies), unregister
    // automatically
    awaitClose { unregisterReceiver(receiver) }
}
