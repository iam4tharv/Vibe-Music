package com.music.vibe.data

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Debug logging for code both applications run.
 *
 * Silent on logcat until an application installs a [sink], while retaining a
 * bounded in-memory ring buffer of recent diagnostic events ([recentEntries])
 * so playback, network, and database failures can be inspected in tests and
 * diagnostic dumps instead of disappearing silently.
 */
object DebugLog {
    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    data class Entry(
        val timestampMs: Long,
        val level: Char,
        val tag: String,
        val message: String,
        val errorSummary: String? = null,
    )

    private const val MAX_RING_ENTRIES = 250
    private val ringBuffer = ConcurrentLinkedDeque<Entry>()
    private val ringSize = AtomicInteger(0)

    @Volatile
    var sink: Sink? = null

    private fun record(level: Char, tag: String, message: String, error: Throwable?) {
        val summary = error?.let { "${it::class.simpleName ?: "Throwable"}: ${it.message.orEmpty()}" }
        ringBuffer.addLast(
            Entry(
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                errorSummary = summary,
            ),
        )
        if (ringSize.incrementAndGet() > MAX_RING_ENTRIES) {
            if (ringBuffer.pollFirst() != null) {
                ringSize.decrementAndGet()
            }
        }
        sink?.log(level, tag, message, error)
    }

    fun recentEntries(limit: Int = MAX_RING_ENTRIES): List<Entry> =
        ringBuffer.toList().takeLast(limit.coerceAtLeast(0))

    fun clearEntries() {
        ringBuffer.clear()
        ringSize.set(0)
    }

    fun d(tag: String, message: String) = record('D', tag, message, null)

    fun i(tag: String, message: String) = record('I', tag, message, null)

    fun w(tag: String, message: String) = record('W', tag, message, null)

    fun w(tag: String, message: String, error: Throwable) = record('W', tag, message, error)

    fun e(tag: String, message: String) = record('E', tag, message, null)

    fun e(tag: String, message: String, error: Throwable) = record('E', tag, message, error)
}

/**
 * Cancellation-safe replacement for bare `runCatching { ... }` in I/O, IPC,
 * database, and parser boundaries.
 *
 * - Always rethrows [CancellationException] so structured concurrency is never broken.
 * - Logs any other [Throwable] to [DebugLog] with [tag] and [context] so errors
 *   are observable rather than silently swallowed.
 */
inline fun <T> runCatchingLogged(
    tag: String,
    context: String = "",
    block: () -> T,
): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    val msg = if (context.isNotBlank()) {
        "$context failed: ${t.message ?: t::class.simpleName}"
    } else {
        t.message ?: t::class.simpleName ?: "Unexpected error"
    }
    DebugLog.w(tag, msg, t)
    Result.failure(t)
}
