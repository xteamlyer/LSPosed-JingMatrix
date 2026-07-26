package org.matrix.vector.daemon.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.HookedProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorAppService"

// Hardcoded transaction code from BridgeService
const val BRIDGE_TRANSACTION_CODE =
    ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code
const val DEX_TRANSACTION_CODE =
    ('_'.code shl 24) or ('D'.code shl 16) or ('E'.code shl 8) or 'X'.code
const val OBFUSCATION_MAP_TRANSACTION_CODE =
    ('_'.code shl 24) or ('O'.code shl 16) or ('B'.code shl 8) or 'F'.code

object ApplicationService : ILSPApplicationService.Stub() {

  data class ProcessKey(val uid: Int, val pid: Int)

  private val processes = ConcurrentHashMap<ProcessKey, ProcessInfo>()

  /**
   * One module generation loaded into one process: the unit a hot reload request addresses. Targets
   * are derived from the process registry rather than registered by the injected process itself, so
   * a target exists as soon as the daemon has handed the module out - including in system_server,
   * whose modules are loaded long before a module-initiated registration could be answered.
   */
  class HotReloadTarget(
      val id: Long,
      val modulePackageName: String,
      val processName: String,
      val uid: Int,
      val pid: Int,
      // Moves to the installed version once a reload of this target succeeds; until then the target
      // keeps reporting STALE.
      @Volatile var loadedVersionCode: Long,
      val hotReloadable: Boolean,
  ) {
    val state = AtomicInteger(HookedProcess.TARGET_STATE_UP_TO_DATE)
  }

  private val hotReloadTargets = ConcurrentHashMap<Long, HotReloadTarget>()

  // Ids are framework-assigned and never reused, as HookedProcess.targetId requires.
  private val nextHotReloadTargetId = AtomicLong(1)

  private class ProcessInfo(val key: ProcessKey, val processName: String, val heartBeat: IBinder) :
      IBinder.DeathRecipient {
    /** Hot reload target ids owned by this process, keyed by module package name. */
    val targetIds = ConcurrentHashMap<String, Long>()

    /** Set once while the process bootstraps; null until then, and for legacy-only processes. */
    @Volatile var hotReloadBinder: IHotReloadTarget? = null

    init {
      heartBeat.linkToDeath(this, 0)
      processes[key] = this
    }

    override fun binderDied() {
      heartBeat.unlinkToDeath(this, 0)
      processes.remove(key)
      targetIds.values.forEach { hotReloadTargets.remove(it) }
    }
  }

  /** Records the module generations handed to [info], assigning a target id to each. */
  private fun recordHotReloadTargets(info: ProcessInfo, modules: List<Module>) {
    for (module in modules) {
      info.targetIds.computeIfAbsent(module.packageName) {
        val id = nextHotReloadTargetId.getAndIncrement()
        hotReloadTargets[id] =
            HotReloadTarget(
                id = id,
                modulePackageName = module.packageName,
                processName = info.processName,
                uid = info.key.uid,
                pid = info.key.pid,
                loadedVersionCode = module.versionCode,
                // Hot reload is specified only for modules with exactly one Java entry class.
                hotReloadable = module.file.moduleClassNames.size == 1,
            )
        id
      }
    }
  }

  /**
   * All processes currently hooked by [modulePackageName]. Not filtered to hot-reloadable targets:
   * getRunningTargets() is documented as returning hooked processes, and a target that cannot be
   * reloaded answers UNSUPPORTED rather than being hidden.
   */
  fun getHotReloadTargets(modulePackageName: String): List<HookedProcess> {
    val installedVersion = ConfigCache.state.modules[modulePackageName]?.versionCode
    return hotReloadTargets.values
        .filter { it.modulePackageName == modulePackageName }
        .map { target ->
          HookedProcess().apply {
            targetId = target.id
            uid = target.uid
            pid = target.pid
            processName = target.processName
            state = reportedState(target, installedVersion)
            loadedVersionCode = target.loadedVersionCode
          }
        }
  }

  /**
   * A target running a generation older than the installed one is STALE. Without this the state a
   * module app polls for never changes, and the documented "notice STALE, then request a reload"
   * flow has nothing to notice. RELOADING and FAILED describe the last attempt and outrank it.
   */
  private fun reportedState(target: HotReloadTarget, installedVersion: Long?): Int {
    val state = target.state.get()
    if (state != HookedProcess.TARGET_STATE_UP_TO_DATE) return state
    return if (installedVersion != null && installedVersion != target.loadedVersionCode) {
      HookedProcess.TARGET_STATE_STALE
    } else {
      state
    }
  }

  /** Drops every target of [modulePackageName], for when it is disabled or uninstalled. */
  fun forgetHotReloadTargets(modulePackageName: String) {
    hotReloadTargets.values.removeIf { it.modulePackageName == modulePackageName }
    processes.values.forEach { it.targetIds.remove(modulePackageName) }
  }

  /** Targets of [modulePackageName] that are running an older generation than the installed one. */
  fun staleHotReloadTargets(modulePackageName: String): List<HotReloadTarget> {
    val installedVersion = ConfigCache.state.modules[modulePackageName]?.versionCode ?: return emptyList()
    return hotReloadTargets.values.filter {
      it.modulePackageName == modulePackageName &&
          it.hotReloadable &&
          it.loadedVersionCode != installedVersion
    }
  }

  /** Resolves a target id, but only for the module that owns it. */
  fun getHotReloadTarget(targetId: Long, modulePackageName: String): HotReloadTarget? =
      hotReloadTargets[targetId]?.takeIf { it.modulePackageName == modulePackageName }

  /**
   * Claims [target] for a reload. Reloads are serialized per target, so the check and the transition
   * have to be one atomic step rather than a read followed by a write.
   */
  fun beginHotReload(target: HotReloadTarget): Boolean {
    while (true) {
      val current = target.state.get()
      if (current == HookedProcess.TARGET_STATE_RELOADING) return false
      if (target.state.compareAndSet(current, HookedProcess.TARGET_STATE_RELOADING)) return true
    }
  }

  fun endHotReload(target: HotReloadTarget, state: Int, loadedVersionCode: Long? = null) {
    loadedVersionCode?.let { target.loadedVersionCode = it }
    target.state.set(state)
  }

  /** The reload entry point of the process holding [target], or null if it never registered one. */
  fun getHotReloadBinder(target: HotReloadTarget): IHotReloadTarget? =
      processes[ProcessKey(target.uid, target.pid)]?.hotReloadBinder

  override fun registerHotReloadTarget(target: IHotReloadTarget) {
    val info = ensureRegistered()
    info.hotReloadBinder = target
    Log.d(TAG, "Hot reload target registered for ${info.processName} (pid=${info.key.pid})")
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      DEX_TRANSACTION_CODE -> {
        val shm = FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) ?: return false
        reply?.writeNoException()
        reply?.let { shm.writeToParcel(it, 0) }
        reply?.writeLong(shm.size.toLong())
        return true
      }
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        val obfuscation = ConfigCache.state.isDexObfuscateEnabled
        val signatures = ObfuscationManager.getSignatures()
        reply?.writeNoException()
        reply?.writeInt(signatures.size * 2)
        for ((key, value) in signatures) {
          reply?.writeString(key)
          reply?.writeString(if (obfuscation) value else key)
        }
        return true
      }
    }
    return super.onTransact(code, data, reply, flags)
  }

  fun registerHeartBeat(uid: Int, pid: Int, processName: String, heartBeat: IBinder): Boolean {
    return runCatching {
          ProcessInfo(ProcessKey(uid, pid), processName, heartBeat)
          true
        }
        .getOrDefault(false)
  }

  fun hasRegister(uid: Int, pid: Int): Boolean = processes.containsKey(ProcessKey(uid, pid))

  private fun ensureRegistered(): ProcessInfo {
    val key = ProcessKey(getCallingUid(), getCallingPid())
    val info = processes[key]
    if (info == null) {
      Log.w(TAG, "Unauthorized IPC call from uid=${key.uid} pid=${key.pid}")
      throw RemoteException("Not registered")
    }
    return info
  }

  private fun getAllModules(): List<Module> {
    val info = ensureRegistered()
    if (info.key.uid == Process.SYSTEM_UID && info.processName == "system") {
      return ConfigCache.getModulesForSystemServer()
    }
    if (ManagerService.isRunningManager(getCallingPid(), info.key.uid)) {
      return emptyList()
    }
    return ConfigCache.getModulesForProcess(info.processName, info.key.uid)
  }

  override fun getModulesList() =
      getAllModules().filter { !it.file.legacy }.also { recordHotReloadTargets(ensureRegistered(), it) }

  override fun getLegacyModulesList() = getAllModules().filter { it.file.legacy }

  override fun isLogMuted(): Boolean = !ManagerService.isVerboseLog

  override fun getPrefsPath(packageName: String): String {
    val info = ensureRegistered()
    return ConfigCache.getPrefsPath(packageName, info.key.uid)
  }

  override fun requestInjectedManagerBinder(
      binderList: MutableList<IBinder>
  ): ParcelFileDescriptor? {
    val info = ensureRegistered()
    val pid = info.key.pid
    val uid = info.key.uid

    if (ManagerService.postStartManager(pid) || ConfigCache.isManager(uid)) {
      binderList.add(ManagerService.obtainManagerBinder(info.heartBeat, pid, uid))
    }

    return runCatching {
          // Verify the APK signature before serving it
          InstallerVerifier.verifyInstallerSignature(FileSystem.managerApkPath.toString())
          ParcelFileDescriptor.open(
              FileSystem.managerApkPath.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .onFailure { Log.e(TAG, "Failed to open or verify manager APK", it) }
        .getOrNull()
  }
}
