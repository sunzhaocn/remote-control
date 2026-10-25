package com.simplerc.app.session

/** Bounded diagnostics only; expired or duplicate replies never change the current input result. */
internal class PendingInputAcks(private val lifetimeMs: Long = 3000, private val capacity: Int = 32) {
    private val submitted = LinkedHashMap<Long, Long>()

    init { require(lifetimeMs > 0 && capacity > 0) }

    fun remember(sequence: Long, nowMs: Long) {
        submitted.entries.removeAll { nowMs - it.value !in 0..lifetimeMs }
        submitted.remove(sequence)
        if (submitted.size >= capacity) submitted.remove(submitted.keys.first())
        submitted[sequence] = nowMs
    }

    fun acknowledge(sequence: Long, nowMs: Long): Long? {
        val start = submitted.remove(sequence) ?: return null
        return (nowMs - start).takeIf { it in 0..lifetimeMs }
    }

    fun clear() = submitted.clear()
}
