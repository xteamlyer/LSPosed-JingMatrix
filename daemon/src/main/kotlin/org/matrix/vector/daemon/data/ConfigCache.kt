package org.matrix.vector.daemon.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageParser
import android.system.Os
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.models.Application
import org.lsposed.lspd.models.Module
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.VectorDaemon
import org.matrix.vector.daemon.ipc.InjectedModuleService
import org.matrix.vector.daemon.system.*
import org.matrix.vector.daemon.utils.InstallerVerifier
import org.matrix.vector.daemon.utils.applySqliteHelperWorkaround
import org.matrix.vector.daemon.utils.getRealUsers

private const val TAG = "VectorConfigCache"

object ConfigCache {
  // Module preference operations are delegated to PreferenceStore
  // Writable operations of modules are delegated to ModuleDatabase

  @Volatile
  var state = DaemonState()
    private set


  // Module package -> the packages it claims, for modules whose module.prop fixes the scope.
  // Absent means the module places no restriction on its scope.
  @Volatile private var staticScopes: Map<String, Set<String>> = emptyMap()

  /** The packages [modulePackage] claims, or null when it does not fix its scope. */
  fun staticScopeOf(modulePackage: String): Set<String>? = staticScopes[modulePackage]

  private val cacheUpdateChannel = Channel<Unit>(Channel.CONFLATED)

  init {
    VectorDaemon.scope.launch {
      for (request in cacheUpdateChannel) {
        performCacheUpdate()
      }
    }
    applySqliteHelperWorkaround()
  }

  private fun ensureCacheReady() {
    if (!state.isCacheReady && packageManager?.asBinder()?.isBinderAlive == true) {
      synchronized(this) {
        if (!state.isCacheReady) {
          Log.i(TAG, "System services are ready. Mapping modules and scopes.")
          updateManager(false)
          setupMiscPath()
          performCacheUpdate()
          state = state.copy(isCacheReady = true)
        }
      }
    }
  }

  fun updateManager(uninstalled: Boolean) {
    if (uninstalled) {
      state = state.copy(managerUid = -1)
      return
    }
    runCatching {
          val info =
              packageManager?.getPackageInfoCompat(BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME, 0, 0)
          val uid = info?.applicationInfo?.uid
          val installedApkPath = info?.applicationInfo?.sourceDir
          if (uid == null || installedApkPath == null) {
            Log.i(TAG, "Manager is not installed")
            state = state.copy(managerUid = -1)
            return
          }

          InstallerVerifier.verifyInstallerSignature(installedApkPath)
          Log.i(TAG, "Manager verified and found at UID: $uid")
          state = state.copy(managerUid = uid)
        }
        .onFailure { state = state.copy(managerUid = -1) }
  }

  private fun setupMiscPath() {
    if (state.miscPath != null) return

    val pathStr = PreferenceStore.getModulePrefs("lspd", 0, "config")["misc_path"] as? String
    val path =
        if (pathStr == null) {
          val newPath = Paths.get("/data/misc", UUID.randomUUID().toString())
          PreferenceStore.updateModulePref("lspd", 0, "config", "misc_path", newPath.toString())
          newPath
        } else {
          Paths.get(pathStr)
        }
    state = state.copy(miscPath = path)

    runCatching {
          val perms =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x"))
          Files.createDirectories(state.miscPath!!, perms)
          FileSystem.setSelinuxContextRecursive(state.miscPath!!, "u:object_r:xposed_data:s0")
        }
        .onFailure { Log.e(TAG, "Failed to create misc directory", it) }
  }

  fun isManager(uid: Int): Boolean {
    ensureCacheReady()
    return uid == state.managerUid || uid == BuildConfig.MANAGER_INJECTED_UID
  }

  fun requestCacheUpdate() {
    cacheUpdateChannel.trySend(Unit)
  }

  /** Builds a completely new Immutable State and atomically swaps it. */
  private fun performCacheUpdate() {
    if (packageManager == null) return

    Log.d(TAG, "Executing Cache Update...")
    val oldState = state

    val newModules = mutableMapOf<String, Module>()
    val newStaticScopes = mutableMapOf<String, Set<String>>()
    // Deleted from the configuration: the package is not installed for any user, so what it was
    // configured to do cannot mean anything.
    val obsoleteModules = mutableSetOf<String>()
    val obsoletePaths = mutableMapOf<String, String>()
    // Kept in the configuration and reported: enabled, installed, and not loadable. The two used to
    // be one set, so a module whose APK would not parse was quietly un-enabled — the user had
    // asked for it, the switch went off by itself, and nothing said why.
    val unloadable = mutableMapOf<String, Int>()

    ModuleDatabase.enabledModuleRows().forEach { row ->
      val pkgName = row.packageName
      var apkPath = row.apkPath
      if (pkgName == "lspd") return@forEach

      val oldModule = oldState.modules[pkgName]

      var pkgInfo: android.content.pm.PackageInfo? = null
      val users = userManager?.getRealUsers() ?: emptyList()
      for (user in users) {
        pkgInfo = packageManager?.getPackageInfoCompat(pkgName, MATCH_ALL_FLAGS, user.id)
        if (pkgInfo?.applicationInfo != null) break
      }

      // Gone, not broken. No user has this package any more, so the configuration for it is
      // meaningless and is cleaned up. This is the only case that deletes anything.
      if (pkgInfo?.applicationInfo == null) {
        Log.w(TAG, "Failed to find package info of $pkgName")
        obsoleteModules.add(pkgName)
        return@forEach
      }

      val appInfo = pkgInfo.applicationInfo

      if (oldModule != null &&
          appInfo?.sourceDir != null &&
          apkPath != null &&
          oldModule.apkPath != null &&
          FileSystem.toGlobalNamespace(apkPath).exists() &&
          apkPath == oldModule.apkPath &&
          File(appInfo.sourceDir).parent == File(apkPath).parent) {

        if (oldModule.appId == -1) oldModule.applicationInfo = appInfo
        // This path skips re-reading the APK, so what the module claims has to be carried
        // over; the new map replaces the old one wholesale and would otherwise lose it.
        staticScopes[pkgName]?.let { newStaticScopes[pkgName] = it }
        newModules[pkgName] = oldModule
        return@forEach
      }

      val realApkPath = getModuleApkPath(appInfo!!)
      if (realApkPath == null) {
        // Installed, enabled, and not loadable. Deleting the row here would silently un-enable a
        // module the user did enable, and they would find the switch off with no reason given.
        // The configuration stands; what could not be done is recorded and reported instead.
        Log.w(TAG, "Failed to find path of $pkgName")
        unloadable[pkgName] = ILSPManagerService.MODULE_LOAD_NO_APK
        return@forEach
      }
      apkPath = realApkPath
      obsoletePaths[pkgName] = realApkPath

      FileSystem.readStaticScope(apkPath)?.let { newStaticScopes[pkgName] = it }

      val preLoadedApk = FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled)
      if (preLoadedApk != null) {
        val module =
            Module().apply {
              packageName = pkgName
              this.apkPath = apkPath
              appId = appInfo.uid
              applicationInfo = appInfo
              service = oldModule?.service ?: InjectedModuleService(pkgName)
              file = preLoadedApk
            }
        newModules[pkgName] = module
      } else {
        // As above: a module whose DEX will not parse is broken, not unwanted.
        Log.w(TAG, "Failed to parse DEX/ZIP for $pkgName, skipping.")
        unloadable[pkgName] = ILSPManagerService.MODULE_LOAD_BAD_DEX
      }
    }

    if (packageManager?.asBinder()?.isBinderAlive == true) {
      obsoleteModules.forEach { ModuleDatabase.removeModule(it) }
      obsoletePaths.forEach { (pkg, path) -> ModuleDatabase.updateModuleApkPath(pkg, path, true) }
    }

    staticScopes = newStaticScopes
    // Rows can predate the module declaring a fixed scope, or come from an older build that let
    // them in. Dropping them here is what makes the scope actually fixed rather than merely
    // unreachable through the manager, and it runs before the scope table is read below.
    newStaticScopes.forEach { (modulePkg, claimed) ->
      val dropped = ModuleDatabase.pruneScopeToClaimed(modulePkg, claimed)
      if (dropped > 0) {
        Log.i(TAG, "Dropped $dropped app(s) outside the static scope of $modulePkg")
      }
    }

    val newScopes = mutableMapOf<ProcessScope, MutableList<Module>>()
    ModuleDatabase.enabledScopeRows().forEach { scopeRow ->
      val appPkg = scopeRow.appPackage
      val modPkg = scopeRow.modulePackage
      val userId = scopeRow.userId

      val module = newModules[modPkg] ?: return@forEach

      if (appPkg == "system") {
        newScopes.getOrPut(ProcessScope("system_server", 1000)) { mutableListOf() }.add(module)
        return@forEach
      }

      val pkgInfo = packageManager?.getPackageInfoWithComponents(appPkg, MATCH_ALL_FLAGS, userId)
      if (pkgInfo?.applicationInfo == null) return@forEach

      val processNames = pkgInfo.fetchProcesses()
      if (processNames.isEmpty()) return@forEach

      val appUid = pkgInfo.applicationInfo!!.uid

      for (processName in processNames) {
        val processScope = ProcessScope(processName, appUid)
        newScopes.getOrPut(processScope) { mutableListOf() }.add(module)

        if (modPkg == appPkg) {
          val appId = appUid % PER_USER_RANGE
          userManager?.getRealUsers()?.forEach { user ->
            val moduleUid = user.id * PER_USER_RANGE + appId
            if (moduleUid != appUid) {
              val moduleSelf = ProcessScope(processName, moduleUid)
              newScopes.getOrPut(moduleSelf) { mutableListOf() }.add(module)
            }
          }
        }
      }
    }

    // --- ATOMIC STATE SWAP ---
    state = oldState.copy(modules = newModules, scopes = newScopes, unloadable = unloadable)

    Log.d(TAG, "Cache Update Complete. Map Swap successful.")
    // Log.d(TAG, "cached modules:")
    // newModules.forEach { (pkg, mod) -> Log.d(TAG, "$pkg ${mod.apkPath}") }

    // Log.d(TAG, "cached scopes:")
    // newScopes.forEach { (ps, modules) ->
    //   Log.d(TAG, "${ps.processName}/${ps.uid}")
    //   modules.forEach { mod -> Log.d(TAG, "\t${mod.packageName}") }
    // }
  }

  fun getModulesForProcess(processName: String, uid: Int): List<Module> {
    ensureCacheReady()
    if (processName == "system_server") {
      Log.w(TAG, "Skip unexpected module queries for $processName")
      return emptyList()
    }
    return state.scopes[ProcessScope(processName, uid)] ?: emptyList()
  }

  fun getModuleByUid(uid: Int): Module? =
      state.modules.values.firstOrNull { it.appId == uid % PER_USER_RANGE }

  fun getModulesForSystemServer(): List<Module> {
    val modules = mutableListOf<Module>()
    if (!android.os.SELinux.checkSELinuxAccess(
        "u:r:system_server:s0", "u:r:system_server:s0", "process", "execmem")) {
      Log.e(TAG, "Skipping system_server injection: sepolicy execmem denied")
      return modules
    }

    val currentState = state

    ModuleDatabase.systemServerModuleRows().forEach { row ->
          run {
            val pkgName = row.packageName
            // A row with no recorded path has never been resolved; the rebuild will fill it in,
            // and injecting from a null path is not something to attempt in the meantime.
            val apkPath = row.apkPath ?: return@forEach

            val cached = currentState.modules[pkgName]
            if (cached != null) {
              modules.add(cached)
              return@forEach
            }

            val statPath = FileSystem.toGlobalNamespace("/data/user_de/0/$pkgName").absolutePath
            val module =
                Module().apply {
                  packageName = pkgName
                  this.apkPath = apkPath
                  appId = runCatching { Os.stat(statPath).st_uid }.getOrDefault(-1)
                  service = InjectedModuleService(pkgName)
                }

            runCatching {
                  @Suppress("DEPRECATION")
                  val pkg = PackageParser().parsePackage(File(apkPath), 0, false)
                  module.applicationInfo = pkg.applicationInfo
                }
                .onFailure {
                  Log.w(TAG, "PackageParser failed for $apkPath, using fallback ApplicationInfo")
                  module.applicationInfo = ApplicationInfo().apply { packageName = pkgName }
                }

            // Always apply the critical paths manually, even on fallback
            module.applicationInfo?.apply {
              sourceDir = apkPath
              dataDir = statPath
              deviceProtectedDataDir = statPath
              HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(this, statPath)
              processName = pkgName
              uid = module.appId
            }

            FileSystem.loadModule(apkPath, state.isDexObfuscateEnabled)?.let {
              module.file = it
              modules.add(module)
              // We intentionally don't mutate state.modules here. Cache update will catch it.
            }
          }
        }
    return modules
  }

  fun getModuleApkPath(info: ApplicationInfo): String? {
    val apks = mutableListOf<String>()
    info.sourceDir?.let { apks.add(it) }
    info.splitSourceDirs?.let { apks.addAll(it) }

    return apks.firstOrNull { apk ->
      runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
              zip.getEntry("META-INF/xposed/java_init.list") != null ||
                  zip.getEntry("assets/xposed_init") != null
            }
          }
          .getOrDefault(false)
    }
  }

  fun getInstalledModules(): List<ApplicationInfo> {
    val allPackages =
        packageManager?.getInstalledPackagesFromAllUsers(MATCH_ALL_FLAGS, false) ?: emptyList()
    return allPackages
        .mapNotNull { it.applicationInfo }
        .filter { info -> getModuleApkPath(info) != null }
  }

  fun shouldSkipProcess(scope: ProcessScope): Boolean {
    ensureCacheReady()
    return !state.scopes.containsKey(scope)
  }

  fun getPrefsPath(packageName: String, uid: Int): String {
    setupMiscPath()
    val basePath = state.miscPath ?: throw IllegalStateException("Fatal: miscPath not initialized!")

    val userId = uid / PER_USER_RANGE
    val userSuffix = if (userId == 0) "" else userId.toString()
    val path = basePath.resolve("prefs$userSuffix").resolve(packageName)

    val module = state.modules[packageName]
    if (module != null && module.appId == uid % PER_USER_RANGE) {
      runCatching {
            // Ensure the directory exists first
            if (!Files.exists(path)) {
              Files.createDirectories(path)
            }

            Files.walk(path).use { stream ->
              stream.forEach { p ->
                val pathStr = p.toString()

                // Change Owner
                Os.chown(pathStr, uid, uid)

                // Set Permissions using Octal
                // Root folder must be word-readable for monitoring
                val mode =
                    when {
                      p == path -> "755".toInt(8) // Root folder: 755
                      Files.isDirectory(p) -> "711".toInt(8) // Sub-folders: 711
                      else -> "744".toInt(8) // Files: 744
                    }

                Os.chmod(pathStr, mode)
              }
            }
          }
          .onFailure { Log.e(TAG, "Failed to prepare prefs path: $path", it) }
    }
    return path.toString()
  }
}
