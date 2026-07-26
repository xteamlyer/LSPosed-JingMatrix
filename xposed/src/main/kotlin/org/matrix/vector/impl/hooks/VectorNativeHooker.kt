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

/**
 * Builder for configuring and registering hooks.
 *
 * [moduleId] identifies the owning module so hook ids can be isolated between modules; it is null for
 * framework-internal hooks, which never use ids.
 */
class VectorHookBuilder(private val origin: Executable, private val moduleId: Any? = null) :
    HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
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

        val id = this.id
        if (id != null) {
            val key = IdKey(moduleId, origin, id)
            val existing = idRegistry[key]
            if (existing != null && existing.installed.get()) {
                // Same (module, executable, id): replace in place via the volatile-write path rather
                // than installing a second native record. Bumping the epoch invalidates the old
                // handle; native registration is untouched.
                val epoch = existing.epoch.incrementAndGet()
                existing.hooker = hooker
                return handleFor(existing, epoch)
            }

            val record = VectorHookRecord(hooker, priority, exceptionMode, id)
            if (
                !HookBridge.hookMethod(true, origin, VectorNativeHooker::class.java, priority, record)
            ) {
                throw HookFailedError("Cannot hook $origin")
            }
            idRegistry[key] = record
            return handleFor(record, record.epoch.get())
        }

        val record = VectorHookRecord(hooker, priority, exceptionMode, null)

        // Register natively. HookBridge now stores VectorHookRecord instead of HookerCallback.
        if (
            !HookBridge.hookMethod(true, origin, VectorNativeHooker::class.java, priority, record)
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        return handleFor(record, record.epoch.get())
    }

    /**
     * Creates a handle bound to [record] and the epoch at which it became valid. The handle is stale
     * once the record is replaced (epoch moves on) or unhooked.
     */
    private fun handleFor(record: VectorHookRecord, epoch: Int): HookHandle =
        object : HookHandle {
            override fun getExecutable(): Executable = origin

            override fun getId(): String? = record.id

            override fun unhook() {
                if (record.installed.compareAndSet(true, false)) {
                    HookBridge.unhookMethod(true, origin, record)
                    record.id?.let { idRegistry.remove(IdKey(moduleId, origin, it), record) }
                }
            }

            override fun replaceHook(hooker: Hooker): HookHandle {
                // Valid only while still installed and no replacement happened since this handle.
                // The epoch CAS also makes concurrent replacements from the same handle mutually
                // exclusive.
                if (!record.installed.get() || !record.epoch.compareAndSet(epoch, epoch + 1)) {
                    throw IllegalStateException("Hook handle is no longer valid")
                }
                record.hooker = hooker
                return handleFor(record, epoch + 1)
            }
        }

    companion object {
        // Registry of id-bearing hooks keyed by (module, executable, id). Lets a repeated intercept()
        // with an already-registered id reuse the installed record. Ids are isolated per module.
        private val idRegistry = ConcurrentHashMap<IdKey, VectorHookRecord>()
    }
}

/** Registry key scoping a hook id to its owning module and executable. */
private data class IdKey(val moduleId: Any?, val executable: Executable, val id: String)

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

        @Suppress("UNCHECKED_CAST") val modernHooks = snapshots[0] as Array<VectorHookRecord>
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
