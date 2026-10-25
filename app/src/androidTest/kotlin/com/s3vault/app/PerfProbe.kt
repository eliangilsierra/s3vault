package com.s3vault.app

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Measures what a user would feel while a scenario runs, from inside the process:
 *
 *  - **UI-thread time per frame** (input + animation + layout/measure + draw-record, from [FrameMetrics]). This is the app's own
 *    cost; the render-thread/GPU part is excluded because the emulator rasterises in software and would only add noise.
 *  - **Main-thread latency**: a helper thread posts a message to the main looper every few milliseconds and records how long
 *    it waited to run. A value of 200 ms means the UI could not react to a touch for 200 ms, whatever the cause (a frame, a
 *    query, a synchronous IPC...). This catches freezes that never produce a janky frame because no frame was drawn.
 *
 * Samples are tagged with the current [phase] so that scrolling and tapping can be reported separately.
 */
class PerfProbe(private val activity: Activity, private val tag: String = "BulkPerf") {
    class Frame(val phase: String, val uiMs: Float, val totalMs: Float)
    class Latency(val phase: String, val ms: Float)

    @Volatile var phase: String = "all"
    private val frames = CopyOnWriteArrayList<Frame>()
    private val latencies = CopyOnWriteArrayList<Latency>()
    private val thread = HandlerThread("perf-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val ui = (m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)) / 1e6f
        frames += Frame(phase, ui, m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6f)
    }

    private val ping = object : Runnable {
        override fun run() {
            if (!running) return
            val sent = System.nanoTime()
            val ph = phase
            main.post { latencies += Latency(ph, (System.nanoTime() - sent) / 1e6f) }
            handler.postDelayed(this, PING_EVERY_MS)
        }
    }

    private class Mem(val heapKb: Long, val pssKb: Int, val gcCount: Long, val allocKb: Long)

    private fun mem(): Mem {
        val rt = Runtime.getRuntime()
        val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        return Mem(
            (rt.totalMemory() - rt.freeMemory()) / 1024, info.totalPss,
            android.os.Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: 0L,
            (android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLongOrNull() ?: 0L) / 1024,
        )
    }

    private var memAtStart: Mem? = null
    private var memAtStop: Mem? = null

    fun start() {
        frames.clear(); latencies.clear()
        memAtStart = mem()
        running = true
        activity.window.addOnFrameMetricsAvailableListener(frameListener, handler)
        handler.post(ping)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(frameListener) }
        Thread.sleep(150) // let the last posted pings and frame callbacks land
        memAtStop = mem()
    }

    fun close() { stop(); thread.quitSafely() }

    private fun pct(sorted: List<Float>, p: Double): Float =
        if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    /** One line per phase: frame count, UI-thread time percentiles, janky frames (> 16.7 ms of UI work), and main-thread latency. */
    fun report(label: String): String {
        val phases = (frames.map { it.phase } + latencies.map { it.phase }).distinct()
        val out = StringBuilder()
        for (p in phases) {
            val f = frames.filter { it.phase == p }.map { it.uiMs }.sorted()
            val l = latencies.filter { it.phase == p }.map { it.ms }.sorted()
            val line = "$label [$p] frames=${f.size} ui p50=${"%.1f".format(pct(f, .5))} p90=${"%.1f".format(pct(f, .9))} " +
                "p99=${"%.1f".format(pct(f, .99))} max=${"%.1f".format(f.lastOrNull() ?: 0f)} ms; " +
                "over16.7=${f.count { it > 16.7f }} over50=${f.count { it > 50f }} over100=${f.count { it > 100f }}; " +
                "mainLatency pings=${l.size} p99=${"%.0f".format(pct(l, .99))} max=${"%.0f".format(l.lastOrNull() ?: 0f)} ms " +
                "over50=${l.count { it > 50f }} over200=${l.count { it > 200f }}"
            Log.i(tag, line)
            out.appendLine(line)
        }
        return out.toString()
    }

    /** Heap and PSS at the start and end of the run, GCs that happened in between and how much was allocated. */
    fun memoryReport(label: String): String {
        val a = memAtStart ?: return ""
        val b = memAtStop ?: mem()
        val line = "$label memory: java heap ${a.heapKb} -> ${b.heapKb} KiB, PSS ${a.pssKb} -> ${b.pssKb} KiB, " +
            "gc runs=${b.gcCount - a.gcCount}, allocated=${(b.allocKb - a.allocKb) / 1024} MiB"
        Log.i(tag, line)
        return line
    }

    fun maxLatencyMs(phase: String? = null): Float = latencies.filter { phase == null || it.phase == phase }.maxOfOrNull { it.ms } ?: 0f
    fun framesOver(ms: Float, phase: String? = null): Int = frames.count { (phase == null || it.phase == phase) && it.uiMs > ms }
    fun uiP90(phase: String? = null): Float = pct(frames.filter { phase == null || it.phase == phase }.map { it.uiMs }.sorted(), .9)
    fun uiMax(phase: String? = null): Float = frames.filter { phase == null || it.phase == phase }.maxOfOrNull { it.uiMs } ?: 0f

    companion object {
        private const val PING_EVERY_MS = 8L
    }
}
