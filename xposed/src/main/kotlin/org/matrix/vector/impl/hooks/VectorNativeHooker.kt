package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.error.HookFailedError
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.util.Utils
import org.matrix.vector.impl.di.VectorBootstrap
import org.matrix.vector.nativebridge.HookBridge

/** Builder for configuring and registering hooks. */
class VectorHookBuilder(private val modulePackageName: String, private val origin: Executable) :
    HookBuilder {

    constructor(origin: Executable) : this(FRAMEWORK_HOOK_OWNER, origin)

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
        validateHookTarget()
        if (HookRegistry.isFrozen(modulePackageName, hooker)) {
            throw IllegalStateException("Module $modulePackageName is frozen for hot reload")
        }

        val hookKey = id?.let { HookKey(modulePackageName, origin, it) }
        val record = createRecord(hooker)

        if (hookKey != null) {
            synchronized(HookRegistry) {
                val existing = HookRegistry.records[hookKey]
                installRecord(record)
                HookRegistry.records[hookKey] = record
                if (existing != null) {
                    uninstallRecord(existing)
                }
                return VectorHookHandle(record, hookKey)
            }
        }

        installRecord(record)
        return VectorHookHandle(record, null)
    }

    private fun createRecord(hooker: Hooker): VectorHookRecord =
        VectorHookRecord(
            modulePackageName = modulePackageName,
            executable = origin,
            id = id,
            priority = priority,
            hooker = hooker,
            exceptionMode = exceptionMode,
        )

    private fun validateHookTarget() {
        if (Modifier.isAbstract(origin.modifiers)) {
            throw IllegalArgumentException("Cannot hook abstract methods: $origin")
        } else if (origin.declaringClass.classLoader == VectorHookBuilder::class.java.classLoader) {
            throw IllegalArgumentException("Do not allow hooking inner methods")
        } else if (
            origin is Method &&
                origin.declaringClass == Method::class.java &&
                origin.name == "invoke"
        ) {
            throw IllegalArgumentException("Cannot hook Method.invoke")
        }
    }
}

private const val FRAMEWORK_HOOK_OWNER = "org.matrix.vector.framework"

private data class HookKey(
    val modulePackageName: String,
    val executable: Executable,
    val id: String,
)

private object HookRegistry {
    val records = ConcurrentHashMap<HookKey, VectorHookRecord>()
    val allRecords = ConcurrentHashMap.newKeySet<VectorHookRecord>()
    private val frozenLoaders = ConcurrentHashMap<String, MutableSet<ClassLoader>>()

    fun freeze(modulePackageName: String, classLoaders: Collection<ClassLoader>) {
        frozenLoaders[modulePackageName] =
            ConcurrentHashMap.newKeySet<ClassLoader>().apply { addAll(classLoaders) }
    }

    fun unfreeze(modulePackageName: String) {
        frozenLoaders.remove(modulePackageName)
    }

    fun isFrozen(modulePackageName: String, hooker: Hooker): Boolean {
        val classLoader = hooker.javaClass.classLoader ?: return false
        return frozenLoaders[modulePackageName]?.contains(classLoader) == true
    }

    fun handlesForModule(modulePackageName: String): List<HookHandle> {
        return allRecords
            .filter { it.modulePackageName == modulePackageName && it.isActive() }
            .map {
                VectorHookHandle(
                    it,
                    it.id?.let { id -> HookKey(modulePackageName, it.executable, id) },
                )
            }
    }
}

internal fun getActiveHookHandles(modulePackageName: String): List<HookHandle> {
    return HookRegistry.handlesForModule(modulePackageName)
}

internal fun freezeHooks(modulePackageName: String, classLoaders: Collection<ClassLoader>) {
    HookRegistry.freeze(modulePackageName, classLoaders)
}

internal fun unfreezeHooks(modulePackageName: String) {
    HookRegistry.unfreeze(modulePackageName)
}

internal fun unhookAllModuleHooks(modulePackageName: String, except: Set<HookHandle> = emptySet()) {
    val excludedRecords = except.mapNotNull { (it as? VectorHookHandle)?.record }.toSet()
    HookRegistry.allRecords
        .filter { it.modulePackageName == modulePackageName && it !in excludedRecords }
        .forEach(::uninstallRecord)
}

private class VectorHookHandle(val record: VectorHookRecord, private val hookKey: HookKey?) :
    HookHandle {
    override fun getExecutable(): Executable = record.executable

    override fun getId(): String? = record.id

    override fun unhook() {
        if (uninstallRecord(record)) {
            hookKey?.let { key -> HookRegistry.records.remove(key, record) }
        }
    }

    override fun replaceHook(hooker: Hooker): HookHandle {
        if (!record.isActive()) {
            throw IllegalStateException("Hook handle is no longer valid")
        }
        val replacement =
            VectorHookRecord(
                modulePackageName = record.modulePackageName,
                executable = record.executable,
                id = record.id,
                priority = record.priority,
                hooker = hooker,
                exceptionMode = record.exceptionMode,
            )

        synchronized(HookRegistry) {
            if (!record.isActive()) {
                throw IllegalStateException("Hook handle is no longer valid")
            }
            // TODO(API102): implement native HookBridge atomic replacement. The current
            // Java-level active-record swap prevents duplicate Java callbacks, but native
            // install/uninstall is not a single primitive and is not full API 102 compliance.
            installRecord(replacement)
            hookKey?.let { key -> HookRegistry.records[key] = replacement }
            uninstallRecord(record)
        }
        return VectorHookHandle(replacement, hookKey)
    }
}

private fun installRecord(record: VectorHookRecord) {
    if (
        !HookBridge.hookMethod(
            true,
            record.executable,
            VectorNativeHooker::class.java,
            record.priority,
            record,
        )
    ) {
        throw HookFailedError("Cannot hook ${record.executable}")
    }
    HookRegistry.allRecords.add(record)
}

private fun uninstallRecord(record: VectorHookRecord): Boolean {
    if (!record.deactivate()) return false
    HookBridge.unhookMethod(true, record.executable, record)
    record.id?.let { id ->
        HookRegistry.records.remove(
            HookKey(record.modulePackageName, record.executable, id),
            record,
        )
    }
    HookRegistry.allRecords.remove(record)
    return true
}

/**
 * The native callback entrypoint. Instantiated natively by [HookBridge] when a hooked method is
 * hit.
 */
class VectorNativeHooker<T : Executable>(private val method: T) {

    private val isStatic = Modifier.isStatic(method.modifiers)
    private val returnType = if (method is Method) method.returnType else null

    /** Invoked by C++ via JNI. */
    fun callback(args: Array<Any?>): Any? {
        val thisObject = if (isStatic) null else args[0]
        val actualArgs = if (isStatic) args else args.sliceArray(1 until args.size)

        // Retrieve the hook snapshots
        val snapshots = HookBridge.callbackSnapshot(VectorHookRecord::class.java, method)

        @Suppress("UNCHECKED_CAST")
        val modernHooks =
            (snapshots[0] as Array<VectorHookRecord>).filter { it.isActive() }.toTypedArray()
        val legacyHooks = snapshots[1]

        // Fast path: No hooks active
        if (modernHooks.isEmpty() && legacyHooks.isEmpty()) {
            return invokeOriginalSafely(thisObject, actualArgs)
        }

        val terminal: (Any?, Array<Any?>) -> Any? = { tObj, tArgs ->
            val delegate = VectorBootstrap.delegate
            if (legacyHooks.isNotEmpty() && delegate != null) {
                delegate.processLegacyHook(method, tObj, tArgs, legacyHooks) {
                    invokeOriginalSafely(tObj, tArgs)
                }
            } else {
                invokeOriginalSafely(tObj, tArgs)
            }
        }

        val rootChain = VectorChain(method, thisObject, actualArgs, modernHooks, 0, terminal)

        val result = rootChain.proceed()

        // Type safety validation before returning to C++
        if (returnType != null && returnType != Void.TYPE) {
            if (result == null) {
                if (returnType.isPrimitive) {
                    throw NullPointerException(
                        "Hook returned null for a primitive return type: $method"
                    )
                }
            } else {
                // Use the JNI bridge for the most reliable type check across ClassLoaders
                if (
                    !HookBridge.instanceOf(result, returnType) &&
                        !isBoxingCompatible(result, returnType)
                ) {
                    Utils.logD(
                        "Hook return type mismatch. Expected ${returnType.name}, got ${result.javaClass.name}"
                    )
                }
            }
        }

        return result
    }

    /** Handles primitive boxing compatibility (e.g., Integer object vs int primitive). */
    private fun isBoxingCompatible(obj: Any, targetType: Class<*>): Boolean {
        if (!targetType.isPrimitive) return false
        return when (targetType) {
            Int::class.javaPrimitiveType -> obj is Int
            Long::class.javaPrimitiveType -> obj is Long
            Boolean::class.javaPrimitiveType -> obj is Boolean
            Double::class.javaPrimitiveType -> obj is Double
            Float::class.javaPrimitiveType -> obj is Float
            Byte::class.javaPrimitiveType -> obj is Byte
            Char::class.javaPrimitiveType -> obj is Char
            Short::class.javaPrimitiveType -> obj is Short
            else -> false
        }
    }

    /** Safely invokes the original method, unwrapping InvocationTargetExceptions. */
    private fun invokeOriginalSafely(tObj: Any?, tArgs: Array<Any?>): Any? {
        return try {
            HookBridge.invokeOriginalMethod(method, tObj, *tArgs)
        } catch (ite: InvocationTargetException) {
            throw ite.cause ?: ite
        }
    }
}
