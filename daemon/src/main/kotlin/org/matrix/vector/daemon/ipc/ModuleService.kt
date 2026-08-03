package org.matrix.vector.daemon.ipc

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.NotificationManager
import org.matrix.vector.daemon.system.ProcessFreezer
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.activityManager

private const val TAG = "VectorModuleService"

class ModuleService(private val loadedModule: Module) : IXposedService.Stub() {

  companion object {
    // Per-target serialization lives on the target itself; this only keeps one slow target from
    // delaying another.
    private val hotReloadExecutor =
        Executors.newCachedThreadPool { r -> Thread(r, "vector-hot-reload") }

    private val uidSet = ConcurrentHashMap.newKeySet<Int>()
    private val serviceMap = Collections.synchronizedMap(WeakHashMap<Module, ModuleService>())

    fun uidClear() {
      uidSet.clear()
    }

    fun uidStarts(uid: Int) {
      if (uidSet.add(uid)) {
        val module = ConfigCache.getModuleByUid(uid)
        if (module?.file?.legacy == false) {
          val service = serviceMap.getOrPut(module) { ModuleService(module) }
          service.sendBinder(uid)
        }
      }
    }

    fun uidGone(uid: Int) {
      uidSet.remove(uid)
    }

    // Drives the same cycle as a service request, so onHotReloading can still refuse it.
    fun autoHotReload(module: Module) {
      if (!module.file.autoHotReload) return
      val service = serviceMap.getOrPut(module) { ModuleService(module) }
      ApplicationService.staleHotReloadTargets(module.packageName).forEach { target ->
        if (target.hotReloadable && ApplicationService.beginHotReload(target)) {
          Log.d(TAG, "Auto hot reloading ${module.packageName} in ${target.processName}")
          hotReloadExecutor.execute { service.runHotReload(target, null, null) }
        }
      }
    }
  }

  /**
   * Forges a ContentProvider call to force the module's target app process to receive this Binder
   * IPC endpoint without standard Context.bindService() limits.
   */
  private fun sendBinder(uid: Int) {
    val name = loadedModule.packageName
    runCatching {
          val userId = uid / PER_USER_RANGE
          val authority = name + AUTHORITY_SUFFIX
          val provider =
              activityManager?.getContentProviderExternal(authority, userId, null, null)?.provider

          if (provider == null) {
            Log.d(TAG, "No service provider for $name")
            return
          }

          val extra = Bundle().apply { putBinder("binder", asBinder()) }
          val reply: Bundle? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                provider.call(
                    AttributionSource.Builder(1000).setPackageName("android").build(),
                    authority,
                    SEND_BINDER,
                    null,
                    extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                provider.call("android", null, authority, SEND_BINDER, null, extra)
              } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                provider.call("android", authority, SEND_BINDER, null, extra)
              } else {
                provider.call("android", SEND_BINDER, null, extra)
              }

          if (reply != null) Log.d(TAG, "Sent module binder to $name")
          else Log.w(TAG, "Failed to send module binder to $name")
        }
        .onFailure { Log.w(TAG, "Failed to send module binder for uid $uid", it) }
  }

  private fun ensureModule(): Int {
    val appId = Binder.getCallingUid() % PER_USER_RANGE
    if (loadedModule.appId != appId) {
      throw RemoteException(
          "Module ${loadedModule.packageName} is not for uid ${Binder.getCallingUid()}")
    }
    return Binder.getCallingUid() / PER_USER_RANGE
  }

  override fun getApiVersion() = ensureModule().let { IXposedService.LIB_API }

  override fun getFrameworkName() = ensureModule().let { BuildConfig.FRAMEWORK_NAME }

  /**
   * The whole version, not just its name.
   *
   * The interface promises a module "the framework version" as a string, and what goes in it is
   * this implementation's to decide. "2.0" was true and useless: the number that identifies a
   * build is the commit count, and even that is shared by every branch built at the same depth, so
   * a module author reading a bug report could not tell which framework produced it. The manager's
   * status page grew the exact build for that reason; a module author receives bug reports too.
   *
   * The parenthesised group stays purely numeric and [getFrameworkVersionCode] still answers with
   * the number on its own, so nothing that wants to *compare* versions has any reason to parse
   * this string.
   */
  override fun getFrameworkVersion() =
      ensureModule().let {
        buildString {
          append(BuildConfig.VERSION_NAME)
          append(" (").append(BuildConfig.VERSION_CODE).append(")")
          BuildConfig.VERSION_HASH.takeIf { hash -> hash.isNotBlank() }
              ?.let { hash -> append(" ").append(hash) }
        }
      }

  override fun getFrameworkVersionCode() = ensureModule().let { BuildConfig.VERSION_CODE }

  override fun getFrameworkProperties(): Long {
    ensureModule()
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled)
        prop = prop or IXposedService.PROP_RT_API_PROTECTION
    return prop
  }

  override fun getScope(): List<String> {
    ensureModule()
    // The scope table has one row per (app, user), so a module enabled for several users saw the
    // same package repeatedly. A scope is a set of package names.
    return ModuleDatabase.getModuleScope(loadedModule.packageName)?.map { it.packageName }?.distinct()
        ?: emptyList()
  }

  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
    val userId = ensureModule()
    if (packages.isEmpty()) {
      // Nothing was asked for, so the request is trivially satisfied. Returning without touching
      // the callback would leave the module waiting forever.
      callback.onScopeRequestApproved(emptyList())
      return
    }
    // A module that fixed its own scope in module.prop does not get to ask for more of it at
    // runtime. Prompting the user here would make "fixed" mean nothing.
    ConfigCache.staticScopeOf(loadedModule.packageName)?.let { claimed ->
      val beyond = packages.filterNot { claimed.contains(it) }
      if (beyond.isNotEmpty()) {
        callback.onScopeRequestFailed(
            "This module declares a static scope, so ${beyond.joinToString()} cannot be added")
        return
      }
    }
    if (!PreferenceStore.isScopeRequestBlocked(loadedModule.packageName)) {
      packages.forEach { pkg ->
        NotificationManager.requestModuleScope(loadedModule.packageName, userId, pkg, callback)
      }
    } else {
      callback.onScopeRequestFailed("Scope request blocked by user configuration")
    }
  }

  override fun removeScope(packages: List<String>) {
    val userId = ensureModule()
    packages.forEach { pkg ->
      runCatching { ModuleDatabase.removeModuleScope(loadedModule.packageName, pkg, userId) }
          .onFailure { Log.e(TAG, "Error removing scope for $pkg", it) }
    }
  }

  override fun getRunningTargets(): List<HookedProcess> {
    ensureModule()
    return ApplicationService.getHotReloadTargets(loadedModule.packageName)
  }

  override fun hotReloadModule(targetId: Long, data: Bundle?, callback: IHotReloadCallback?) {
    ensureModule()
    // SecurityException is reserved by the AIDL for exactly these two conditions, so it must not be
    // raised for anything else on this path - a module-thrown SecurityException in particular has
    // to reach the caller as a FAILED result, not as "invalid target id".
    val target =
        ApplicationService.getHotReloadTarget(targetId, loadedModule.packageName)
            ?: throw SecurityException("Target $targetId is not a target of ${loadedModule.packageName}")

    if (!target.hotReloadable) {
      // Hot reload is specified only for modules declaring exactly one Java entry class.
      report(callback, IXposedService.HOT_RELOAD_UNSUPPORTED, "Module has no single Java entry class")
      return
    }

    if (!ApplicationService.beginHotReload(target)) {
      report(callback, IXposedService.HOT_RELOAD_IN_PROGRESS, "A reload is already running")
      return
    }

    // The AIDL asks implementations to validate and enqueue promptly and report through the
    // callback. Running the cycle inline would pin this binder thread for its whole duration and
    // ANR a module app that called from its main thread.
    hotReloadExecutor.execute { runHotReload(target, data, callback) }
  }

  private fun runHotReload(
      target: ApplicationService.HotReloadTarget,
      data: Bundle?,
      callback: IHotReloadCallback?,
  ) {
    var status = IXposedService.HOT_RELOAD_FAILED
    var message: String? = "Hot reload did not run"
    var refreeze: (() -> Unit)? = null
    var loadedVersion: Long? = null

    try {
      val binder = ApplicationService.getHotReloadBinder(target)
      if (binder == null) {
        status = IXposedService.HOT_RELOAD_UNSUPPORTED
        message = "Process ${target.processName} has no hot reload entry point"
        return
      }
      if (!binder.asBinder().isBinderAlive) {
        status = IXposedService.HOT_RELOAD_PROCESS_DIED
        message = "Process ${target.processName} is gone"
        return
      }
      val newModule = ConfigCache.state.modules[loadedModule.packageName]
      if (newModule == null) {
        status = IXposedService.HOT_RELOAD_UNSUPPORTED
        message = "No installed generation of ${loadedModule.packageName} to load"
        return
      }

      // A cached target is usually frozen, and a transaction to a frozen process never reaches the
      // module. Thawing first is what keeps that case from being reported as a refusal.
      refreeze = ProcessFreezer.thaw(target.uid, target.pid)
      if (refreeze == null && ProcessFreezer.isFrozen(target.uid, target.pid)) {
        status = IXposedService.HOT_RELOAD_FAILED
        message = "Target process is frozen and could not be thawed"
        return
      }

      val outcome = binder.hotReload(loadedModule.packageName, data, newModule)
      status = outcome.status
      // Whether the generation was swapped is not the same question as whether the reload
      // succeeded: onHotReloaded runs after the swap is committed, so a throw from it leaves the
      // process on the new code and still reports FAILED. Recording the version the target is
      // actually running is what keeps getRunningTargets() honest about it.
      if (outcome.generationChanged) loadedVersion = newModule.versionCode
      // A null message is reserved for a refusal, so anything else gets one supplied.
      message =
          outcome.message
              ?: if (status == IXposedService.HOT_RELOAD_FAILED && !outcome.refused) {
                "Hot reload failed without a diagnostic message"
              } else {
                null
              }
    } catch (e: DeadObjectException) {
      status = IXposedService.HOT_RELOAD_PROCESS_DIED
      message = "Process ${target.processName} died during hot reload"
    } catch (t: Throwable) {
      status = IXposedService.HOT_RELOAD_FAILED
      message = "${t.javaClass.name}: ${t.message ?: "no message"}"
      Log.e(TAG, "Hot reload of ${loadedModule.packageName} failed", t)
    } finally {
      refreeze?.invoke()
      ApplicationService.endHotReload(target, stateFor(status), loadedVersion)
      report(callback, status, message)
    }
  }

  private fun stateFor(status: Int): Int =
      when (status) {
        IXposedService.HOT_RELOAD_SUCCEEDED -> HookedProcess.TARGET_STATE_UP_TO_DATE
        IXposedService.HOT_RELOAD_FAILED -> HookedProcess.TARGET_STATE_FAILED
        // Unsupported and process-died say nothing about the generation the target is running, so
        // the reported state falls back to comparing versions.
        else -> HookedProcess.TARGET_STATE_UP_TO_DATE
      }

  private fun report(callback: IHotReloadCallback?, status: Int, message: String?) {
    runCatching { callback?.onHotReloadResult(status, message) }
        .onFailure { Log.w(TAG, "Cannot deliver hot reload result to ${loadedModule.packageName}", it) }
  }

  override fun requestRemotePreferences(group: String): Bundle {
    val userId = ensureModule()
    return Bundle().apply {
      putSerializable(
          "map",
          PreferenceStore.getModulePrefs(loadedModule.packageName, userId, group) as Serializable)
    }
  }

  @Suppress("DEPRECATION")
  override fun updateRemotePreferences(group: String, diff: Bundle) {
    val userId = ensureModule()
    val values = mutableMapOf<String, Any?>()

    // RemotePreferences.Editor always writes this key, and sets it for edit().clear(). Ignoring it
    // left every key the module app just cleared in place.
    if (diff.getBoolean("clear", false)) {
      PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    }

    diff.getSerializable("delete")?.let { deletes ->
      (deletes as Set<*>).forEach { values[it as String] = null }
    }
    diff.getSerializable("put")?.let { puts ->
      (puts as Map<*, *>).forEach { (k, v) -> values[k as String] = v }
    }

    runCatching {
          PreferenceStore.updateModulePrefs(loadedModule.packageName, userId, group, values)
          (loadedModule.service as? InjectedModuleService)
              ?.onUpdateRemotePreferences(group, userId, diff)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemotePreferences(group: String) {
    val userId = ensureModule()
    PreferenceStore.deleteModulePrefs(loadedModule.packageName, userId, group)
    // Hooked processes hold an in-process cache of the group; without this they keep serving the
    // deleted values until their process restarts.
    (loadedModule.service as? InjectedModuleService)
        ?.onUpdateRemotePreferences(group, userId, Bundle().apply { putBoolean("clear", true) })
  }

  override fun listRemoteFiles(): Array<String> {
    val userId = ensureModule()
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .toFile()
              .list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          val file =
              FileSystem.resolveModuleDir(
                      loadedModule.packageName, "files", userId, Binder.getCallingUid())
                  .resolve(path)
                  .toFile()
          ParcelFileDescriptor.open(
              file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemoteFile(path: String): Boolean {
    val userId = ensureModule()
    FileSystem.ensureModuleFilePath(path)
    return runCatching {
          FileSystem.resolveModuleDir(
                  loadedModule.packageName, "files", userId, Binder.getCallingUid())
              .resolve(path)
              .toFile()
              .delete()
        }
        .getOrElse { throw RemoteException(it.message) }
  }
}
