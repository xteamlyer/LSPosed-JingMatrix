package org.matrix.vector.daemon.system

import android.util.Log
import java.io.File

private const val TAG = "VectorFreezer"

/**
 * Thaws a frozen process for the duration of a daemon-initiated transaction.
 *
 * Android freezes cached processes, and a module's hooked targets are usually cached ones. A binder
 * transaction is not delivered to a frozen process, so without this a hot reload of a backgrounded
 * target fails without ever running module code - indistinguishable from the module returning false
 * from onHotReloading, which is the one case the API reserves a null message for.
 */
object ProcessFreezer {

  /**
   * The freezer file for one process, or null when this device has none for it.
   *
   * Where it lives is the kernel's answer rather than ours: the `0::` line of `/proc/<pid>/cgroup`
   * is that process's own cgroup v2 path relative to the mount point, so reading it is the one form
   * that holds whatever the layout is. A guessed list does not - SM-A145R on Android 15 uses
   * `/uid_<uid>/pid_<pid>`, with no `apps/` or `system/` above it, and the paths this was first
   * written with matched nothing at all there.
   *
   * Only the process's own group is ever returned. The uid-level group holds every process of the
   * app, and thawing there would move processes this reload has no business touching. minSdk is 27,
   * and the cgroup v2 freezer does not exist across that whole range, so null is an ordinary answer
   * rather than a failure.
   */
  private fun freezeFile(pid: Int): File? {
    val path =
        runCatching {
              File("/proc/$pid/cgroup")
                  .readLines()
                  .firstOrNull { it.startsWith("0::") }
                  ?.removePrefix("0::")
                  ?.trim()
                  ?.takeIf { it.isNotEmpty() && it != "/" }
            }
            .getOrNull() ?: return null

    // A group shared with the whole uid is not ours to thaw.
    if (!path.contains("/pid_")) return null

    return File("/sys/fs/cgroup$path/cgroup.freeze").takeIf { it.exists() }
  }

  fun isFrozen(pid: Int): Boolean =
      runCatching { freezeFile(pid)?.readText()?.trim() == "1" }.getOrDefault(false)

  /**
   * Thaws the process if it is frozen, and returns the action that puts it back. Null when nothing
   * was changed - either there is no freezer here or the process was already running.
   *
   * The restore re-reads the file rather than writing "1" blindly: the framework's own app
   * compaction owns this state too, and if it has thawed the process meanwhile - because the user
   * brought the app to the foreground - freezing it again from here would stop a process the system
   * believes is running.
   */
  fun thaw(pid: Int): (() -> Unit)? {
    val file = freezeFile(pid) ?: return null
    val wasFrozen = runCatching { file.readText().trim() == "1" }.getOrDefault(false)
    if (!wasFrozen) return null

    val thawed = runCatching { file.writeText("0") }.isSuccess
    if (!thawed) {
      Log.w(TAG, "Cannot thaw pid=$pid through ${file.path}")
      return null
    }

    Log.d(TAG, "Thawed pid=$pid for a daemon transaction")
    return {
      runCatching {
            if (file.readText().trim() == "0") {
              file.writeText("1")
            } else {
              Log.d(TAG, "Left pid=$pid alone: something else changed its freezer state")
            }
          }
          .onFailure { Log.w(TAG, "Cannot re-freeze pid=$pid", it) }
    }
  }
}
