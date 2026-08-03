package org.matrix.vector.impl.hooks

import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.lsposed.lspd.util.Utils

/** Represents a registered hook configuration, stored natively by [HookBridge]. */
class VectorHookRecord(
    // Mutable so a hook can be replaced in place. The native layer indexes this record by object
    // identity, so swapping the hooker is invisible to it: there is always exactly one record in the
    // callback map, hence no window in which two hookers both look active.
    @Volatile var hooker: Hooker,
    val priority: Int,
    val exceptionMode: ExceptionMode,
    val id: String?,
) {
    // Bumped on every replacement; a hook handle stays valid only while its captured value matches.
    val epoch = AtomicInteger(0)

    // Cleared on unhook to make unhook idempotent and to invalidate outstanding handles.
    val installed = AtomicBoolean(true)
}

/**
 * Core interceptor chain engine. Manages recursive hook execution and enforces [ExceptionMode]
 * protections.
 */
class VectorChain
private constructor(
    private val executable: Executable,
    private val thisObj: Any?,
    private val args: Array<Any?>,
    private val hooks: Array<VectorHookRecord>,
    // Frozen snapshot of the hookers, captured once at the root so replacing a hooker mid-call does
    // not affect this in-flight call (the chain is snapshot based).
    private val hookers: Array<Hooker>,
    private val hookIndex: Int,
    private val terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
) : Chain {

    /** Entry point used to start a call; freezes the current hooker list once for the whole call. */
    constructor(
        executable: Executable,
        thisObj: Any?,
        args: Array<Any?>,
        hooks: Array<VectorHookRecord>,
        hookIndex: Int,
        terminal: (thisObj: Any?, args: Array<Any?>) -> Any?,
    ) : this(
        executable,
        thisObj,
        args,
        hooks,
        Array(hooks.size) { hooks[it].hooker },
        hookIndex,
        terminal,
    )

    // Tracks if this specific chain node has forwarded execution downstream
    internal var proceedCalled: Boolean = false
        private set

    // Stores the actual result/exception from the rest of the chain/original method
    internal var downstreamResult: Any? = null
    internal var downstreamThrowable: Throwable? = null

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObj

    override fun getArgs(): List<Any?> = Collections.unmodifiableList(args.toMutableList())

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = internalProceed(thisObj, args)

    override fun proceed(currentArgs: Array<Any?>): Any? = internalProceed(thisObj, currentArgs)

    override fun proceedWith(thisObject: Any): Any? = internalProceed(thisObject, args)

    override fun proceedWith(thisObject: Any, currentArgs: Array<Any?>): Any? =
        internalProceed(thisObject, currentArgs)

    private fun internalProceed(thisObject: Any?, currentArgs: Array<Any?>): Any? {
        proceedCalled = true

        // Reached the end of the modern hooks; trigger the original executable (and legacy hooks)
        if (hookIndex >= hooks.size) {
            return executeDownstream { terminal(thisObject, currentArgs) }
        }

        val hooker = hookers[hookIndex]
        val exceptionMode = hooks[hookIndex].exceptionMode
        val nextChain =
            VectorChain(
                executable,
                thisObject,
                currentArgs,
                hooks,
                hookers,
                hookIndex + 1,
                terminal,
            )

        return try {
            executeDownstream { hooker.intercept(nextChain) }
        } catch (t: Throwable) {
            executeDownstream {
                handleInterceptorException(
                    t,
                    hooker,
                    exceptionMode,
                    nextChain,
                    thisObject,
                    currentArgs,
                )
            }
        }
    }

    /**
     * Executes the block and caches the downstream state so parent chains can recover it if the
     * current interceptor crashes during post-processing.
     */
    private inline fun executeDownstream(block: () -> Any?): Any? {
        return try {
            val result = block()
            downstreamResult = result
            downstreamThrowable = null
            result
        } catch (t: Throwable) {
            downstreamResult = null
            downstreamThrowable = t
            throw t
        }
    }

    /** Handles exceptions thrown by a hooker according to its [ExceptionMode]. */
    private fun handleInterceptorException(
        t: Throwable,
        hooker: Hooker,
        exceptionMode: ExceptionMode,
        nextChain: VectorChain,
        recoveryThis: Any?,
        recoveryArgs: Array<Any?>,
    ): Any? {
        // Check if the exception originated from downstream (lower hooks or original method)
        if (nextChain.proceedCalled && t === nextChain.downstreamThrowable) {
            throw t
        }

        // Passthrough mode does not rescue the process from hooker crashes
        if (exceptionMode == ExceptionMode.PASSTHROUGH) {
            throw t
        }

        val hookerName = hooker.javaClass.name
        if (!nextChain.proceedCalled) {
            // Crash occurred before calling proceed(); skip hooker and continue the chain
            Utils.logD("Hooker [$hookerName] crashed before proceed. Skipping.", t)
            return nextChain.internalProceed(recoveryThis, recoveryArgs)
        } else {
            // Crash occurred after calling proceed(); suppress and restore downstream state
            Utils.logD("Hooker [$hookerName] crashed after proceed. Restoring state.", t)
            nextChain.downstreamThrowable?.let { throw it }
            return nextChain.downstreamResult
        }
    }
}
