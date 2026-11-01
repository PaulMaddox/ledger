package com.pam.ledger.web

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Demo-only retention, independent of browser activity and the host's timezone. */
internal class DemoMaintenance(
    directory: Path,
    private val clock: Clock,
    private val reset: () -> Unit,
    private val checkCapacity: () -> Unit,
) : AutoCloseable {
    private val deadlineFile = directory.resolve("demo-reset-at.txt")
    private var deadline = if (Files.exists(deadlineFile)) {
        Instant.parse(Files.readString(deadlineFile).trim())
    } else nextReset(clock.instant()).also(::saveDeadline)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "demo-maintenance").apply { isDaemon = true }
    }

    fun start() {
        checkNow() // Catch up a missed purge after a process restart, before serving requests.
        scheduler.scheduleWithFixedDelay({
            try { checkNow() } catch (e: Exception) {
                System.err.println("Demo maintenance failed: ${e.message}")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private var pendingDeadline: Instant? = null

    @Synchronized internal fun checkNow() {
        if (!clock.instant().isBefore(deadline)) {
            reset()
            // A completed purge must not repeat merely because persistence fails.
            deadline = nextReset(clock.instant())
            pendingDeadline = deadline
        }
        try {
            pendingDeadline?.let { next ->
                saveDeadline(next)
                pendingDeadline = null
            }
        } finally {
            checkCapacity()
        }
    }

    private fun saveDeadline(value: Instant) {
        // A process killed during persistence must leave an old (overdue), not truncated, deadline.
        val temporary = Files.createTempFile(deadlineFile.parent, "demo-reset-at-", ".tmp")
        try {
            Files.writeString(temporary, value.toString())
            try {
                Files.move(temporary, deadlineFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, deadlineFile, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        // Interrupting a drain can abandon the writer before its journals and lock are closed.
        scheduler.shutdown()
        check(scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) { "demo maintenance did not stop" }
    }

    companion object {
        internal fun nextReset(now: Instant): Instant {
            val local = now.atZone(ZoneId.of("Asia/Dubai"))
            val today = local.toLocalDate().atTime(3, 0).atZone(local.zone)
            return (if (today.toInstant().isAfter(now)) today else today.plusDays(1)).toInstant()
        }
    }
}
