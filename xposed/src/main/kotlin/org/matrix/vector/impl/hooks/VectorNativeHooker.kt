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
 * Builder for configuring and registering hooks. [moduleId] scopes hook ids per module and is null
 * for framework hooks; [frozen] gates registration only, so a retired generation can still unhook.
 */
class VectorHookBuilder(
    private val origin: Executable,
    private val moduleId: Any? = null,
    private val frozen: (() -> Boolean)? = null,
) : HookBuilder {

    private var priority = XposedInterface.PRIORITY_DEFAULT
    private var exceptionMode = ExceptionMode.DEFAULT
    private var id: String? = null

    override fun setPriority(priority: Int): HookBuilder = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode): HookBuilder = apply {
        this.exceptionMode = mode
    }

    override fun setId(id: String?): HookBuilder = apply { this.id = id }

    override fun intercept(hooker: Hooker): HookHandle {
        if (frozen?.invoke() == true) {
            throw IllegalStateException(
                "This module generation has been retired by a hot reload and cannot register hooks"
            )
        }
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
            // putIfAbsent is the only atomic point: a check-then-act would let two threads install
            // two records for one id, which is the duplication this design exists to avoid.
            val candidate = VectorHookRecord(hooker, priority, exceptionMode, id)
            while (true) {
                val existing = idRegistry.putIfAbsent(key, candidate) ?: break
                if (existing.installed.get()) {
                    // Replace in place rather than installing a second native record.
                    val epoch = existing.epoch.incrementAndGet()
                    existing.hooker = hooker
                    return handleFor(existing, epoch)
                }
                // The id is held by a record that has since been unhooked; drop it and retry.
                idRegistry.remove(key, existing)
            }

            if (
                !HookBridge.hookMethod(
                    true,
                    origin,
                    VectorNativeHooker::class.java,
                    priority,
                    candidate,
                )
            ) {
                idRegistry.remove(key, candidate)
                throw HookFailedError("Cannot hook $origin")
            }
            track(candidate)
            return handleFor(candidate, candidate.epoch.get())
        }

        val record = VectorHookRecord(hooker, priority, exceptionMode, null)

        // Register natively. HookBridge now stores VectorHookRecord instead of HookerCallback.
        if (
            !HookBridge.hookMethod(true, origin, VectorNativeHooker::class.java, priority, record)
        ) {
            throw HookFailedError("Cannot hook $origin")
        }

        track(record)
        return handleFor(record, record.epoch.get())
    }

    private fun track(record: VectorHookRecord) {
        val moduleId = this.moduleId ?: return
        moduleHooks
            .computeIfAbsent(moduleId) { ConcurrentHashMap.newKeySet() }
            .add(InstalledHook(origin, record))
    }

    private fun handleFor(record: VectorHookRecord, epoch: Int): HookHandle =
        handleFor(origin, moduleId, record, epoch)

    companion object {
        // Keyed by (module, executable, id) so a repeated intercept() reuses the installed record.
        private val idRegistry = ConcurrentHashMap<IdKey, VectorHookRecord>()

        private val moduleHooks = ConcurrentHashMap<Any, MutableSet<InstalledHook>>()

        // Stale once the record is replaced (epoch moves on) or unhooked.
        private fun handleFor(
            origin: Executable,
            moduleId: Any?,
            record: VectorHookRecord,
            epoch: Int,
        ): HookHandle =
            object : HookHandle {
                override fun getExecutable(): Executable = origin

                override fun getId(): String? = record.id

                override fun unhook() {
                    if (record.installed.compareAndSet(true, false)) {
                        HookBridge.unhookMethod(true, origin, record)
                        record.id?.let { idRegistry.remove(IdKey(moduleId, origin, it), record) }
                        moduleId?.let {
                            moduleHooks[it]?.remove(InstalledHook(origin, record))
                        }
                    }
                }

                override fun replaceHook(hooker: Hooker): HookHandle {
                    // The epoch CAS also makes concurrent replacements mutually exclusive.
                    if (!record.installed.get() || !record.epoch.compareAndSet(epoch, epoch + 1)) {
                        throw IllegalStateException("Hook handle is no longer valid")
                    }
                    record.hooker = hooker
                    return handleFor(origin, moduleId, record, epoch + 1)
                }
            }

        // Minted at the current epoch so the receiver can still replace them.
        fun snapshotHandles(moduleId: Any): List<HookHandle> =
            moduleHooks[moduleId]
                ?.filter { it.record.installed.get() }
                ?.map { handleFor(it.origin, moduleId, it.record, it.record.epoch.get()) }
                ?: emptyList()

        fun trackedRecords(moduleId: Any): Set<VectorHookRecord> =
            moduleHooks[moduleId]?.mapTo(mutableSetOf()) { it.record } ?: emptySet()

        // Tracking survives a reload: replaceHook swaps the hooker inside an installed record,
        // so forgetting it would strand a live hook the framework can no longer hand back.
        fun unhookSince(moduleId: Any, keep: Set<VectorHookRecord>) {
            val tracked = moduleHooks[moduleId] ?: return
            tracked
                .filter { it.record !in keep }
                .forEach { hook ->
                    if (hook.record.installed.compareAndSet(true, false)) {
                        HookBridge.unhookMethod(true, hook.origin, hook.record)
                    }
                    tracked.remove(hook)
                    hook.record.id?.let {
                        idRegistry.remove(IdKey(moduleId, hook.origin, it), hook.record)
                    }
                }
        }
    }
}

private data class InstalledHook(val origin: Executable, val record: VectorHookRecord)

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
