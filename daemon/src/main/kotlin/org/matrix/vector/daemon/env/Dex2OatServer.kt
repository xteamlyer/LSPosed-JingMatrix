package org.matrix.vector.daemon.env

import android.net.LocalServerSocket
import android.os.Build
import android.os.FileObserver
import android.os.SELinux
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.matrix.vector.daemon.VectorDaemon

private const val TAG = "VectorDex2Oat"

// Compatibility states mirrored directly from the ILSPManagerService AIDL contract.
val DEX2OAT_OK = ILSPManagerService.DEX2OAT_OK
val DEX2OAT_MOUNT_FAILED = ILSPManagerService.DEX2OAT_MOUNT_FAILED
val DEX2OAT_SEPOLICY_INCORRECT = ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT
val DEX2OAT_SELINUX_PERMISSIVE = ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE
val DEX2OAT_CRASHED = ILSPManagerService.DEX2OAT_CRASHED

object Dex2OatServer {
  private const val WRAPPER32 = "bin/dex2oat32"
  private const val WRAPPER64 = "bin/dex2oat64"
  private const val HOOKER32 = "bin/liboat_hook32.so"
  private const val HOOKER64 = "bin/liboat_hook64.so"

  private val dex2oatArray = arrayOfNulls<String>(6)
  private val fdArray = arrayOfNulls<FileDescriptor>(6)
  private val stateLock = Any()
  private var serverJob: Job? = null
  private var serverSocket: LocalServerSocket? = null
  private val running = AtomicBoolean(false)

  @Volatile
  var compatibility = DEX2OAT_OK
    private set

  private external fun doMountNative(
      enabled: Boolean,
      r32: String?,
      d32: String?,
      r64: String?,
      d64: String?
  )

  private external fun enableDex2OatPropertyFallbackNative(): Boolean

  private var dex2OatPropertyFallbackEnabled = false

  private fun enableDex2OatPropertyFallback(reason: String) {
    if (dex2OatPropertyFallbackEnabled) {
      Log.i(TAG, "Dex2Oat property fallback already enabled, reason=$reason")
      return
    }

    val ok = enableDex2OatPropertyFallbackNative()
    dex2OatPropertyFallbackEnabled = ok

    if (ok) {
      Log.w(TAG, "Enabled dex2oat property fallback: $reason")
    } else {
      Log.e(TAG, "Failed to enable dex2oat property fallback: $reason")
    }
  }

  private external fun setSockCreateContext(context: String?): Boolean

  private external fun getSockPath(): String

  private val selinuxObserver =
      object :
          FileObserver(
              listOf(File("/sys/fs/selinux/enforce"), File("/sys/fs/selinux/policy")),
              CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
          synchronized(this) {
            if (compatibility == DEX2OAT_CRASHED) {
              stopWatching()
              return
            }

            val enforcing =
                runCatching {
                      Files.newInputStream(Paths.get("/sys/fs/selinux/enforce")).use {
                        it.read() == '1'.code
                      }
                    }
                    .getOrDefault(false)

            when {
              !enforcing -> {
                if (compatibility == DEX2OAT_OK) doMount(false)
                compatibility = DEX2OAT_SELINUX_PERMISSIVE
              }
              hasSePolicyErrors() -> {
                if (compatibility == DEX2OAT_OK) doMount(false)
                compatibility = DEX2OAT_SEPOLICY_INCORRECT
              }
              compatibility != DEX2OAT_OK -> {
                doMount(true)
                if (notMounted()) {
                  doMount(false)
                  compatibility = DEX2OAT_MOUNT_FAILED
                  enableDex2OatPropertyFallback("wrapper mount failed after SELinux observe retry")
                  stopWatching()
                } else {
                  compatibility = DEX2OAT_OK
                }
              }
            }
          }
        }
      }

  private fun hasSePolicyErrors(): Boolean {
    return SELinux.checkSELinuxAccess(
        "u:r:untrusted_app:s0", "u:object_r:dex2oat_exec:s0", "file", "execute") ||
        SELinux.checkSELinuxAccess(
            "u:r:untrusted_app:s0", "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans")
  }

  private fun openDex2oat(id: Int, path: String): Boolean {
    return runCatching {
          val fd = Os.open(path, OsConstants.O_RDONLY, 0)
          fdArray[id] = fd
          dex2oatArray[id] = path
        }
        .onFailure { Log.w(TAG, "Failed to open dex2oat resource: $path", it) }
        .isSuccess
  }

  private fun checkAndAddDex2Oat(path: String) {
    val file = File(path)
    if (!file.exists()) return

    runCatching {
          FileInputStream(file).use { fis ->
            val header = ByteArray(5)
            if (fis.read(header) != 5) return
            // Verify ELF Magic: 0x7F 'E' 'L' 'F'
            if (header[0] != 0x7F.toByte() ||
                header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() ||
                header[3] != 'F'.code.toByte())
                return

            val is32Bit = header[4] == 1.toByte()
            val is64Bit = header[4] == 2.toByte()
            val isDebug = path.contains("dex2oatd")

            val index =
                when {
                  is32Bit -> if (isDebug) 1 else 0
                  is64Bit -> if (isDebug) 3 else 2
                  else -> -1
                }

            if (index != -1 && dex2oatArray[index] == null) {
              val fd = Os.open(path, OsConstants.O_RDONLY, 0)
              dex2oatArray[index] = path
              fdArray[index] = fd
              Log.i(TAG, "Detected $path -> Assigned Index $index")
            }
          }
        }
        .onFailure { Log.w(TAG, "Failed to open dex2oat: $path", it) }
  }

  private fun dex2oatCandidates(): List<String> {
    return if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
      listOf(
          "/apex/com.android.runtime/bin/dex2oat",
          "/apex/com.android.runtime/bin/dex2oatd",
          "/apex/com.android.runtime/bin/dex2oat64",
          "/apex/com.android.runtime/bin/dex2oatd64")
    } else {
      listOf(
          "/apex/com.android.art/bin/dex2oat32",
          "/apex/com.android.art/bin/dex2oatd32",
          "/apex/com.android.art/bin/dex2oat64",
          "/apex/com.android.art/bin/dex2oatd64")
    }
  }

  private fun clearDex2OatMountsLocked() {
    val paths = dex2oatCandidates()
    doMountNative(
        false, paths.getOrNull(0), paths.getOrNull(1), paths.getOrNull(2), paths.getOrNull(3))
  }

  private fun closeDex2OatStateLocked() {
    for (i in fdArray.indices) {
      fdArray[i]?.let { fd ->
        runCatching { Os.close(fd) }
            .onFailure { Log.w(TAG, "Failed to close stale dex2oat fd[$i]", it) }
      }
      fdArray[i] = null
      dex2oatArray[i] = null
    }
  }

  private fun reopenDex2OatStateLocked() {
    dex2oatCandidates().forEach { checkAndAddDex2Oat(it) }
    openDex2oat(4, "/data/adb/modules/zygisk_vector/$HOOKER32")
    openDex2oat(5, "/data/adb/modules/zygisk_vector/$HOOKER64")
  }

  private fun sameFile(fd: FileDescriptor, path: String): Boolean {
    return runCatching {
          val fdStat = Os.fstat(fd)
          val pathStat = Os.stat(path)
          fdStat.st_dev == pathStat.st_dev && fdStat.st_ino == pathStat.st_ino
        }
        .getOrDefault(false)
  }

  private fun validateOriginalDex2OatFdLocked(): Boolean {
    val checks = listOf(0 to WRAPPER32, 1 to WRAPPER32, 2 to WRAPPER64, 3 to WRAPPER64)
    for ((index, wrapperPath) in checks) {
      val fd = fdArray[index] ?: continue
      if (sameFile(fd, wrapperPath)) {
        Log.w(TAG, "dex2oat fd[$index] points to Vector wrapper")
        return false
      }
    }
    return true
  }

  private fun resetDex2OatStateLocked(clearMounts: Boolean = false): Boolean {
    if (clearMounts) {
      clearDex2OatMountsLocked()
    }

    closeDex2OatStateLocked()
    reopenDex2OatStateLocked()
    if (validateOriginalDex2OatFdLocked()) return true

    closeDex2OatStateLocked()

    if (!clearMounts) {
      Log.w(TAG, "Detected stale dex2oat wrapper mount; clearing mounts and retrying")
      clearDex2OatMountsLocked()
      reopenDex2OatStateLocked()
      if (validateOriginalDex2OatFdLocked()) return true
      closeDex2OatStateLocked()
    }

    compatibility = DEX2OAT_MOUNT_FAILED
    enableDex2OatPropertyFallback("wrapper mount failed after soft restart recovery")
    return false
  }

  private fun notMounted(): Boolean {
    for (i in 0 until 4) {
      val bin = dex2oatArray[i] ?: continue
      try {
        val apex = Os.stat("/proc/1/root$bin")
        val wrapper = Os.stat(if (i < 2) WRAPPER32 else WRAPPER64)
        if (apex.st_dev != wrapper.st_dev || apex.st_ino != wrapper.st_ino) {
          return true
        }
      } catch (e: ErrnoException) {
        return true
      }
    }
    return false
  }

  private fun doMount(enabled: Boolean) {
    doMountNative(enabled, dex2oatArray[0], dex2oatArray[1], dex2oatArray[2], dex2oatArray[3])
  }

  private fun ensureMountedLocked(): Boolean {
    if (!notMounted()) return true

    doMount(true)
    if (notMounted()) {
      doMount(false)
      compatibility = DEX2OAT_MOUNT_FAILED
      enableDex2OatPropertyFallback("wrapper mount failed after final retry")
      return false
    }
    return true
  }

  fun start() {
    synchronized(stateLock) {
      if (running.get()) {
        Log.d(TAG, "Dex2oat wrapper daemon already running, skip duplicate start")
        return
      }

      cleanupSocketStateLocked()

      if (!resetDex2OatStateLocked()) return

      if (!ensureMountedLocked()) return

      compatibility = DEX2OAT_OK
      selinuxObserver.startWatching()
      selinuxObserver.onEvent(0, null)

      running.set(true)
      // Run the socket accept loop in an IO coroutine
      serverJob = VectorDaemon.scope.launch { runSocketLoop() }
    }
  }

  fun stop(disableMount: Boolean = false) {
    synchronized(stateLock) {
      running.set(false)
      serverJob?.cancel()
      serverJob = null
      cleanupSocketStateLocked()
      selinuxObserver.stopWatching()
      if (disableMount && compatibility == DEX2OAT_OK) {
        doMount(false)
      }
    }
  }

  fun restart() {
    stop()
    start()
  }

  fun refreshMount() {
    var shouldStart = false
    var shouldRestart = false
    synchronized(stateLock) {
      when {
        compatibility == DEX2OAT_CRASHED -> shouldRestart = true
        running.get() && compatibility == DEX2OAT_OK -> ensureMountedLocked()
        !running.get() && compatibility != DEX2OAT_MOUNT_FAILED -> shouldStart = true
      }
    }
    if (shouldRestart) restart()
    if (shouldStart) start()
  }

  private fun runSocketLoop() {
    Log.i(TAG, "Dex2oat wrapper daemon start")
    val sockPath = getSockPath()
    Log.d(TAG, "wrapper path: $sockPath")

    val xposedFile = "u:object_r:xposed_file:s0"
    val dex2oatExec = "u:object_r:dex2oat_exec:s0"

    if (SELinux.checkSELinuxAccess("u:r:dex2oat:s0", dex2oatExec, "file", "execute_no_trans")) {
      SELinux.setFileContext(WRAPPER32, dex2oatExec)
      SELinux.setFileContext(WRAPPER64, dex2oatExec)
      setSockCreateContext("u:r:dex2oat:s0")
    } else {
      SELinux.setFileContext(WRAPPER32, xposedFile)
      SELinux.setFileContext(WRAPPER64, xposedFile)
      setSockCreateContext("u:r:installd:s0")
    }
    SELinux.setFileContext(HOOKER32, xposedFile)
    SELinux.setFileContext(HOOKER64, xposedFile)

    runCatching {
          serverSocket = LocalServerSocket(sockPath)
          setSockCreateContext(null)
          serverSocket!!.use { server ->
            while (running.get()) {
              try {
                // This blocks until the C++ wrapper connects
                server.accept().use { client ->
                  val input = client.inputStream
                  val output = client.outputStream
                  val id = input.read()
                  if (id in fdArray.indices && fdArray[id] != null) {
                    client.setFileDescriptorsForSend(arrayOf(fdArray[id]!!))
                    output.write(1)
                  }
                }
              } catch (e: IOException) {
                if (!running.get()) break
                throw e
              }
            }
          }
        }
        .onFailure {
          Log.e(TAG, "Dex2oat wrapper daemon crashed", it)
          setSockCreateContext(null)
          synchronized(stateLock) {
            running.set(false)
            cleanupSocketStateLocked()
          }
          if (compatibility == DEX2OAT_OK) {
            doMount(false)
            compatibility = DEX2OAT_CRASHED
          }
        }
        .onSuccess {
          synchronized(stateLock) {
            running.set(false)
            cleanupSocketStateLocked()
          }
        }
  }

  private fun cleanupSocketStateLocked() {
    runCatching { serverSocket?.close() }
    serverSocket = null

    val sockPath = runCatching { getSockPath() }.getOrNull()
    if (!sockPath.isNullOrBlank() && sockPath.startsWith("/")) {
      runCatching {
            val socketFile = File(sockPath)
            if (socketFile.exists()) {
              socketFile.delete()
            }
          }
          .onFailure { Log.w(TAG, "Failed to clean stale dex2oat socket file: $sockPath", it) }
    }
  }
}
