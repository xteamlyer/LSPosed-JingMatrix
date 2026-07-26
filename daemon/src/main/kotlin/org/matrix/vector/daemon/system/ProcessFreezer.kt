package org.matrix.vector.daemon.system

import android.util.Log
import java.io.File

private const val TAG = "VectorFreezer"

/**
 * Thaws a frozen process for the duration of a daemon-initiated transaction.
 *
 * Android freezes cached processes, and a module's hooked targets are usually cached ones. A binder
 * transaction does not reach a frozen process, so without this a hot reload of a backgrounded target
 * fails without ever running module code - indistinguishable from the module returning false from
 * onHotReloading, which is the one case the API reserves a null message for.
 */
object ProcessFreezer {

  /**
   * The freezer is a cgroup v2 file. Newer releases give each process its own group; older ones and
   * some vendor trees freeze at the uid level, so both layouts are probed.
   */
  private fun freezeFile(uid: Int, pid: Int): File? =
      sequenceOf(
              "/sys/fs/cgroup/apps/uid_$uid/pid_$pid/cgroup.freeze",
              "/sys/fs/cgroup/system/uid_$uid/pid_$pid/cgroup.freeze",
              "/sys/fs/cgroup/apps/uid_$uid/cgroup.freeze",
              "/sys/fs/cgroup/system/uid_$uid/cgroup.freeze",
          )
          .map(::File)
          .firstOrNull { it.exists() }

  fun isFrozen(uid: Int, pid: Int): Boolean =
      runCatching { freezeFile(uid, pid)?.readText()?.trim() == "1" }.getOrDefault(false)

  /**
   * Thaws the process if it is frozen and returns an action that restores the previous state, or
   * null if nothing was changed. The caller must run the returned action once the transaction is
   * done, otherwise the process is left permanently runnable.
   */
  fun thaw(uid: Int, pid: Int): (() -> Unit)? {
    val file = freezeFile(uid, pid) ?: return null
    val wasFrozen = runCatching { file.readText().trim() == "1" }.getOrDefault(false)
    if (!wasFrozen) return null

    val thawed = runCatching { file.writeText("0") }.isSuccess
    if (!thawed) {
      Log.w(TAG, "Cannot thaw uid=$uid pid=$pid through ${file.path}")
      return null
    }

    Log.d(TAG, "Thawed uid=$uid pid=$pid for a daemon transaction")
    return {
      runCatching { file.writeText("1") }
          .onFailure { Log.w(TAG, "Cannot re-freeze uid=$uid pid=$pid", it) }
    }
  }
}
