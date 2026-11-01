package com.pam.ledger.web

import com.pam.ledger.Ledger
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read-only host/JVM telemetry. Unsupported platform counters are null, never invented zeros. */
class Performance(private val ledger: Ledger, private val readModel: ReadModel, private val directory: Path) : AutoCloseable {
    private val os = ManagementFactory.getOperatingSystemMXBean()
    private val memory = ManagementFactory.getMemoryMXBean()
    private val threads = ManagementFactory.getThreadMXBean()
    private val runtime = ManagementFactory.getRuntimeMXBean()
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans()
    private val points = ArrayDeque<Map<String, Any?>>()
    private val sampler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "administrator-stats").apply { isDaemon = true }
    }
    private var previousTime = System.nanoTime()
    private var previousEntries = ledger.head().size
    private var previousIo: Map<String, Long>? = null
    @Volatile private var failure: Exception? = null

    init {
        try { sample() } catch (e: Exception) {
            sampler.shutdownNow()
            throw e
        }
        sampler.scheduleAtFixedRate({
            try {
                sample()
                failure = null
            } catch (e: Exception) {
                failure = e
                System.err.println("Administrator telemetry failed: ${e.message}")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun processIo(): Map<String, Long>? {
        val path = Path.of("/proc/self/io")
        if (!Files.exists(path)) return null
        return Files.readAllLines(path).associate { line ->
            val parts = line.split(':', limit = 2)
            parts[0] to parts[1].trim().toLong()
        }
    }

    private fun sample() {
        val now = System.nanoTime()
        val seconds = (now - previousTime) / 1_000_000_000.0
        val entries = ledger.head().size
        val io = processIo()
        fun ioRate(key: String): Double? {
            val before = previousIo?.get(key) ?: return null
            val after = io?.get(key) ?: return null
            return (after - before).coerceAtLeast(0) / seconds
        }
        val bean = os as? OperatingSystemMXBean
        val heap = memory.heapMemoryUsage
        val store = Files.getFileStore(directory)
        val journalBytes = listOf(Ledger.ENTRIES_FILE, Ledger.CHECKPOINTS_FILE).sumOf {
            Files.size(directory.resolve(it))
        }
        val point = linkedMapOf<String, Any?>(
            "t" to System.currentTimeMillis(), "entries" to entries,
            "tps" to (entries - previousEntries) / seconds,
            "processCpu" to bean?.processCpuLoad?.takeIf { it >= 0 }?.times(100),
            "systemCpu" to bean?.cpuLoad?.takeIf { it >= 0 }?.times(100),
            "heapUsed" to heap.used, "heapCommitted" to heap.committed,
            "heapMax" to heap.max.takeIf { it >= 0 },
            "nonHeapUsed" to memory.nonHeapMemoryUsage.used,
            "systemMemoryTotal" to bean?.totalMemorySize?.takeIf { it >= 0 },
            "systemMemoryFree" to bean?.freeMemorySize?.takeIf { it >= 0 },
            "storageTotal" to store.totalSpace, "storageFree" to store.usableSpace,
            "journalBytes" to journalBytes, "readBytesPerSec" to ioRate("read_bytes"),
            "writeBytesPerSec" to ioRate("write_bytes"),
            "threads" to threads.threadCount, "peakThreads" to threads.peakThreadCount,
            "gcCount" to collectors.map { it.collectionCount }.takeIf { counts -> counts.all { it >= 0 } }?.sum(),
            "gcMillis" to collectors.map { it.collectionTime }.takeIf { times -> times.all { it >= 0 } }?.sum(),
            "uptimeMillis" to runtime.uptime, "processors" to os.availableProcessors,
            "loadAverage" to os.systemLoadAverage.takeIf { it >= 0 },
            "readModelLag" to (entries - readModel.totals().entries).coerceAtLeast(0),
        )
        synchronized(points) {
            points.addLast(point)
            if (points.size > 180) points.removeFirst()
        }
        previousTime = now
        previousEntries = entries
        previousIo = io
    }

    /** Last three minutes, sampled on the server even while another tab is open. */
    fun view(): Map<String, Any?> {
        failure?.let { throw IllegalStateException("performance counters unavailable: ${it.message}", it) }
        return mapOf("series" to synchronized(points) { points.toList() },
            "directory" to directory.toString(),
            "ioScope" to "Linux process physical I/O, including all JVM activity; cached writes may appear later. Unavailable on other platforms.")
    }

    override fun close() {
        sampler.shutdownNow()
        check(sampler.awaitTermination(5, TimeUnit.SECONDS)) { "telemetry sampler did not stop" }
    }
}
