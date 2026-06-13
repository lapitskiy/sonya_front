package com.example.sonya_front

data class WatchBleAutoConnectPlan(
    val intervalMs: Long,
    val scanWindowMs: Long,
    val fast: Boolean,
)

class WatchBleAutoConnectPolicy {
    private var normalIntervalMs: Long = 15_000L
    private var normalScanWindowMs: Long = 6_000L
    private val fastIntervalMs: Long = 3_000L
    private val fastScanWindowMs: Long = 10_000L
    private val fastWindowDurationMs: Long = 45_000L
    private var fastUntilMs: Long = 0L

    fun configure(intervalMs: Long, scanWindowMs: Long) {
        normalIntervalMs = intervalMs
        normalScanWindowMs = scanWindowMs
    }

    fun triggerFastWindow(nowMs: Long = System.currentTimeMillis()): Long {
        fastUntilMs = nowMs + fastWindowDurationMs
        return fastWindowDurationMs
    }

    fun reset() {
        fastUntilMs = 0L
    }

    fun describe(): String {
        return "normal=${normalIntervalMs}ms/${normalScanWindowMs}ms fast=${fastIntervalMs}ms/${fastScanWindowMs}ms"
    }

    fun plan(nowMs: Long = System.currentTimeMillis()): WatchBleAutoConnectPlan {
        val fast = nowMs < fastUntilMs
        return WatchBleAutoConnectPlan(
            intervalMs = if (fast) fastIntervalMs else normalIntervalMs,
            scanWindowMs = if (fast) fastScanWindowMs else normalScanWindowMs,
            fast = fast,
        )
    }

    fun connectTimeoutMs(nowMs: Long = System.currentTimeMillis()): Long {
        return if (plan(nowMs).fast) 12_000L else 15_000L
    }
}
