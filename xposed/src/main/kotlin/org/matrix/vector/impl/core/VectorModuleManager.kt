package org.matrix.vector.impl.core

import android.os.Build
import android.os.Bundle
import android.os.Process
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.service.IXposedService
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import org.lsposed.lspd.models.HotReloadOutcome
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.hooks.VectorHookBuilder
import org.matrix.vector.impl.utils.VectorModuleClassLoader
import org.matrix.vector.nativebridge.NativeAPI

/**
 * Responsible for loading modules into the target process. Handles ClassLoader isolation and
 * injects the framework context into the module instances.
 */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"

    // Entries are weak on purpose: activeModules owns the only strong reference, and detach()
    // removes it. A reload holds a local strong list for the cycle instead.
    private class Generation(
        val classLoader: ClassLoader,
        val context: VectorContext,
        entries: List<XposedModule>,
        val isSystemServer: Boolean,
        val processName: String,
    ) {
        private val entryRefs = entries.map { WeakReference(it) }

        fun liveEntries(): List<XposedModule> =
            entryRefs.mapNotNull { it.get() }.filter { VectorLifecycleManager.isActive(it) }
    }

    private val generations = ConcurrentHashMap<String, Generation>()

    // Reloads are serialized per module within this process; the daemon serializes per target.
    private val reloadLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Vector framework.
     */
    fun loadModule(module: Module, isSystemServer: Boolean, processName: String): Boolean {
        val (generation, entries) =
            buildGeneration(module, isSystemServer, processName) ?: return false

        entries.forEach { VectorLifecycleManager.activeModules.add(it) }
        generations[module.packageName] = generation

        val param =
            object : ModuleLoadedParam {
                override fun isSystemServer(): Boolean = isSystemServer

                override fun getProcessName(): String = processName
            }
        entries.forEach { entry ->
            runCatching { entry.onModuleLoaded(param) }
                .onFailure { e ->
                    Log.e(TAG, "Error in onModuleLoaded for ${entry.javaClass.name}", e)
                }
        }

        // Register any native JNI entrypoints declared by the module
        module.file.moduleLibraryNames.forEach { libraryName ->
            NativeAPI.recordNativeEntrypoint(libraryName)
        }

        Log.d(TAG, "Loaded module ${module.packageName} successfully.")
        return true
    }

    // Publishes nothing, so a reload can fail before the old generation is touched.
    private fun buildGeneration(
        module: Module,
        isSystemServer: Boolean,
        processName: String,
    ): Pair<Generation, List<XposedModule>>? {
        try {
            Log.d(TAG, "Loading module ${module.packageName}")

            // Construct the native library search path
            val librarySearchPath = buildString {
                // In system_server the in-APK entries below can only ever be refused: /data/app is
                // apk_data_file, which that domain may read and map but never execute. The daemon
                // stages a copy under a label we own for exactly this reason, and it has to come
                // first, because findLibrary answers with the first candidate it can open.
                if (isSystemServer) {
                    module.file.nativeLibraryDir?.let {
                        append(it).append(File.pathSeparator)
                    }
                }
                val abis =
                    if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
                    else Build.SUPPORTED_32_BIT_ABIS
                for (abi in abis) {
                    append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
                }
            }

            // Create the isolated ClassLoader for the module
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    module.file.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                    blockLegacyApi = module.file.targetApiVersion >= 102,
                )

            // Security/Integrity Check: Ensure the module isn't bundling its own API classes
            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !==
                    initLoader
            ) {
                Log.e(TAG, "The Xposed API classes are compiled into ${module.packageName}")
                return null
            }

            // Create the Context that will be injected into the module
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = module.applicationInfo,
                    service = module.service, // Our IPC client
                    defaultExceptionMode =
                        if (module.file.exceptionPassthrough) ExceptionMode.PASSTHROUGH
                        else ExceptionMode.PROTECTIVE,
                )

            // Register any native JNI entrypoints declared by the module. This has to happen before
            // the entry classes run: a module is free to load its libraries from its constructor or
            // from onModuleLoaded, and an entrypoint recorded afterwards is one the dlopen hook has
            // already missed. The legacy loader has always done it in this order.
            module.file.moduleLibraryNames.forEach { libraryName ->
                NativeAPI.recordNativeEntrypoint(libraryName)
            }

            // Instantiate the module entry classes
            val entries = mutableListOf<XposedModule>()
            for (className in module.file.moduleClassNames) {
                runCatching {
                        val moduleClass = moduleClassLoader.loadClass(className)
                        Log.v(TAG, "Loading class $moduleClass")

                        if (!XposedModule::class.java.isAssignableFrom(moduleClass)) {
                            Log.e(TAG, "Class does not extend XposedModule, skipping.")
                            return@runCatching
                        }

                        val constructor = moduleClass.getDeclaredConstructor()
                        constructor.isAccessible = true
                        val moduleInstance = constructor.newInstance() as XposedModule

                        // detach() is per entry: only the instance that calls it stops.
                        moduleInstance.attachFramework(vectorContext) {
                            VectorLifecycleManager.detach(moduleInstance)
                        }

                        entries.add(moduleInstance)
                    }
                    .onFailure { e -> Log.e(TAG, "Failed to instantiate class $className", e) }
            }

            val generation =
                Generation(moduleClassLoader, vectorContext, entries, isSystemServer, processName)
            return generation to entries
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error loading module ${module.packageName}", e)
            return null
        }
    }

    fun hotReload(
        modulePackageName: String?,
        extras: Bundle?,
        newModule: Module?,
    ): HotReloadOutcome {
        val packageName =
            modulePackageName ?: return unsupported("Hot reload was requested without a module")
        val lock = reloadLocks.computeIfAbsent(packageName) { ReentrantLock() }
        if (!lock.tryLock()) {
            return outcome(
                IXposedService.HOT_RELOAD_IN_PROGRESS,
                "A reload of $packageName is already running in this process",
            )
        }
        return try {
            runHotReload(packageName, extras, newModule)
        } catch (t: Throwable) {
            Log.e(TAG, "Hot reload of $packageName failed", t)
            failed(describe(t))
        } finally {
            lock.unlock()
        }
    }

    private fun runHotReload(
        packageName: String,
        extras: Bundle?,
        newModule: Module?,
    ): HotReloadOutcome {
        if (newModule == null) {
            return unsupported("No new generation of $packageName was supplied")
        }
        val old =
            generations[packageName]
                ?: return unsupported(
                    "$packageName is not loaded in ${VectorServiceClient.processName}"
                )
        if (newModule.file.moduleClassNames.size != 1) {
            return unsupported("$packageName does not declare exactly one Java entry class")
        }

        // Keeps the old generation reachable until onHotReloaded has finished.
        val oldEntries = old.liveEntries()
        if (oldEntries.isEmpty()) {
            // Not a refusal: a null message means onHotReloading returned false, and nothing ran.
            Log.w(TAG, "No attached entry of $packageName can accept a hot reload")
            return unsupported("Every entry of $packageName has detached in this process")
        }

        val built =
            buildGeneration(newModule, old.isSystemServer, old.processName)
                ?: return unsupported("Cannot build a new generation of $packageName")
        val (newGeneration, newEntries) = built

        // Before the callback, so registrations from inside it fail while unhook and replace work.
        old.context.freeze()

        var savedState: Any? = null
        val reloadingParam =
            object : HotReloadingParam {
                override fun getExtras(): Bundle? = extras

                override fun setSavedInstanceState(outState: Any?) {
                    rejectOldGenerationState(outState, old.classLoader)
                    savedState = outState
                }
            }

        val accepted =
            try {
                // One refusal cancels the reload for the whole module.
                oldEntries.all { it.onHotReloading(reloadingParam) }
            } catch (t: Throwable) {
                old.context.unfreeze()
                Log.e(TAG, "onHotReloading of $packageName threw", t)
                return failed(describe(t))
            }
        if (!accepted) {
            old.context.unfreeze()
            Log.d(TAG, "$packageName refused the hot reload")
            return refusal()
        }

        // Captured after the freeze and after old code had its chance to unhook.
        val oldHandles = VectorHookBuilder.snapshotHandles(packageName)
        // replaceHook swaps the hooker inside an installed record, so tracking must survive the
        // reload; the rollback below undoes only what the new generation adds on top of this.
        val inherited = VectorHookBuilder.trackedRecords(packageName)

        oldEntries.forEach { VectorLifecycleManager.activeModules.remove(it) }
        // Active before the callback, so an entry detaching from inside it is honoured.
        newEntries.forEach { VectorLifecycleManager.activeModules.add(it) }

        val reloadedParam =
            object : HotReloadedParam {
                override fun isSystemServer(): Boolean = old.isSystemServer

                override fun getProcessName(): String = old.processName

                override fun getExtras(): Bundle? = extras

                override fun getSavedInstanceState(): Any? = savedState

                override fun getOldHookHandles(): List<XposedInterface.HookHandle> = oldHandles
            }

        try {
            // The default onHotReloaded already unhooks these; doing both would double-unhook.
            newEntries.filter { VectorLifecycleManager.isActive(it) }.forEach {
                it.onHotReloaded(reloadedParam)
            }
        } catch (t: Throwable) {
            // Nothing has been committed yet, so the old generation is still the live one.
            VectorHookBuilder.unhookSince(packageName, inherited)
            newEntries.forEach { VectorLifecycleManager.activeModules.remove(it) }
            oldEntries.forEach { VectorLifecycleManager.activeModules.add(it) }
            old.context.unfreeze()
            Log.e(TAG, "onHotReloaded of $packageName threw; kept the previous generation", t)
            return failed(describe(t))
        }

        // Commit only now that the new code has taken over. Replacing the map entry drops the last
        // framework-owned reference to the old generation; oldEntries dies with this frame.
        generations[packageName] = newGeneration
        Log.d(TAG, "Hot reloaded $packageName")
        return outcome(IXposedService.HOT_RELOAD_SUCCEEDED, null)
    }

    /**
     * Rejects saved state that the old generation created, which would otherwise keep the retired
     * classloader reachable through the new one. A shallow scan, as the API describes it: a
     * diagnostic aid rather than an object graph verifier.
     */
    private fun rejectOldGenerationState(state: Any?, oldClassLoader: ClassLoader) {
        if (state == null) return
        reject(state, oldClassLoader)
        when (state) {
            is Array<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Collection<*> -> state.forEach { it?.let { e -> reject(e, oldClassLoader) } }
            is Map<*, *> ->
                state.forEach { (k, v) ->
                    k?.let { reject(it, oldClassLoader) }
                    v?.let { reject(it, oldClassLoader) }
                }
        }
    }

    private fun reject(value: Any, oldClassLoader: ClassLoader) {
        if (definedBy(value.javaClass, oldClassLoader)) {
            throw IllegalArgumentException(
                "Saved instance state contains ${value.javaClass.name}, which was created under " +
                    "the old module classloader"
            )
        }
    }

    private fun definedBy(clazz: Class<*>, classLoader: ClassLoader): Boolean {
        var loader: ClassLoader? =
            (if (clazz.isArray) clazz.componentType else clazz)?.classLoader
        while (loader != null) {
            if (loader === classLoader) return true
            loader = loader.parent
        }
        return false
    }

    private fun outcome(status: Int, message: String?, refused: Boolean = false) =
        HotReloadOutcome().apply {
            this.status = status
            this.message = message
            this.refused = refused
        }

    private fun unsupported(message: String) = outcome(IXposedService.HOT_RELOAD_UNSUPPORTED, message)

    private fun failed(message: String) = outcome(IXposedService.HOT_RELOAD_FAILED, message)

    private fun refusal() = outcome(IXposedService.HOT_RELOAD_FAILED, null, refused = true)

    private fun describe(t: Throwable) = "${t.javaClass.name}: ${t.message ?: "no message"}"
}
