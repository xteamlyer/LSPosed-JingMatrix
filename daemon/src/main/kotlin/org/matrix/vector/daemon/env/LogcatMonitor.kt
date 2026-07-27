package org.matrix.vector.daemon.env

import android.annotation.SuppressLint
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SELinux
import android.os.SystemProperties
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import org.matrix.vector.daemon.data.FileSystem

private const val TAG = "VectorLogcat"
private const val FD_MODE =
    ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or
        ParcelFileDescriptor.MODE_TRUNCATE or
        ParcelFileDescriptor.MODE_APPEND

object LogcatMonitor {
  private var modulesFd = -1
  private var verboseFd = -1
  @Volatile private var isRunning = false

  private external fun runLogcat()

  // Thread-safe LRU implementation for log files
  private class ThreadSafeLRU(private val maxEntries: Int = 10) {
    private val map = LinkedHashMap<File, Unit>(maxEntries, 1f, false)

    @Synchronized
    fun add(file: File) {
      map[file] = Unit
      if (map.size > maxEntries) {
        val eldest = map.keys.first()
        if (eldest.delete()) {
          map.remove(eldest)
        }
      }
    }
  }

  private val moduleLogs = ThreadSafeLRU()
  private val verboseLogs = ThreadSafeLRU()

  init {
    loadNativeLibrary() // still needed by ObfuscationManager / Dex2OatServer JNI

    // Meizu log_reject_level workaround
    if (SystemProperties.getInt("persist.sys.log_reject_level", 0) > 0) {
      SystemProperties.set("persist.sys.log_reject_level", "0")
    }

    // Vector: log dir rotation and props/dmesg dumping disabled
  }

  @SuppressLint("UnsafeDynamicallyLoadedCode")
  private fun loadNativeLibrary() {
    val classPath = System.getProperty("java.class.path", "")
    val abi =
        if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS[0] else Build.SUPPORTED_32_BIT_ABIS[0]
    System.load("$classPath!/lib/$abi/${System.mapLibraryName("daemon")}")
  }

  // Vector: dumpPropsAndDmesg() removed - no longer called, no props.txt/kmsg.log written

  fun start() {
    // Vector: logcat capture daemon disabled, never spawns runLogcat()
  }

  fun getVerboseLog(): File? = fdToPath(verboseFd)?.toFile()

  fun getModulesLog(): File? = fdToPath(modulesFd)?.toFile()

  private fun fdToPath(fd: Int) = if (fd == -1) null else Paths.get("/proc/self/fd", fd.toString())

  /** Resurrects deleted log files from /proc/self/fd if an external process deletes them. */
  private fun checkFd(fd: Int) {
    if (fd == -1) return
    runCatching {
          val jfd = FileDescriptor()
          jfd.javaClass
              .getDeclaredMethod("setInt\$", Int::class.java)
              .apply { isAccessible = true }
              .invoke(jfd, fd)
          val stat = Os.fstat(jfd)

          // st_nlink == 0 means the file was deleted but the FD is still held open
          if (stat.st_nlink == 0L) {
            val file = Files.readSymbolicLink(fdToPath(fd)!!)
            val parent = file.parent
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
              if (FileSystem.chattr0(parent)) Files.deleteIfExists(parent)
            }
            val name = file.fileName.toString()
            val originName = name.substring(0, name.lastIndexOf(' '))
            Files.copy(file, parent.resolve(originName))
          }
        }
        .onFailure { Log.w(TAG, "checkFd failed for $fd", it) }
  }

  fun startVerbose() {}

  fun stopVerbose() {}

  fun refresh(isVerboseLog: Boolean) {}

  fun checkLogFile() {}

  @Suppress("unused") // Called via JNI
  private fun refreshFd(isVerboseLog: Boolean): Int {
    return runCatching {
          val logFile =
              if (isVerboseLog) {
                checkFd(verboseFd)
                val f = FileSystem.getNewVerboseLogPath()
                verboseLogs.add(f)
                f
              } else {
                checkFd(modulesFd)
                val f = FileSystem.getNewModulesLogPath()
                moduleLogs.add(f)
                f
              }

          Log.i(TAG, "New log file: $logFile")
          FileSystem.chattr0(logFile.toPath().parent)
          val fd = ParcelFileDescriptor.open(logFile, FD_MODE).detachFd()

          if (isVerboseLog) verboseFd = fd else modulesFd = fd
          fd
        }
        .onFailure {
          if (isVerboseLog) verboseFd = -1 else modulesFd = -1
          Log.w(TAG, "refreshFd failed", it)
        }
        .getOrDefault(-1)
  }
}
