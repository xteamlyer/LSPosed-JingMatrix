package org.matrix.vector.impl

import android.util.Log

/**
 * Optional framework log sink supplied by the embedding runtime.
 *
 * Core always writes logcat itself. The sink only mirrors the structured event, keeping file
 * paths, storage policy and I/O out of the reusable Vector runtime.
 */
object VectorLogBridge {

    fun interface Sink {
        fun log(priority: Int, tag: String, message: String, throwable: Throwable?)
    }

    @Volatile private var sink: Sink? = null

    @JvmStatic
    fun setSink(newSink: Sink?) {
        sink = newSink
    }

    fun log(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val logcatMessage =
            if (throwable == null) {
                message
            } else {
                "$message\n${Log.getStackTraceString(throwable)}"
            }
        Log.println(priority, tag, logcatMessage)
        runCatching { sink?.log(priority, tag, message, throwable) }
    }
}
