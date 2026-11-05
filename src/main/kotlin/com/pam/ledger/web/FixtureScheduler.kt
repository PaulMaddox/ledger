package com.pam.ledger.web

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Owns the demo's standing pool independently of simulated customers. Results are
 * persisted before dispatch; shutdown drains admitted settlements without interruption.
 */
class FixtureScheduler(
    private val book: Book,
    private val failed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    automatic: Boolean = true,
) : AutoCloseable {
    private val lock = book.fixtureLifecycle
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fixture-scheduler").apply { isDaemon = true }
    }
    @Volatile var lastTick = 0L
        private set
    @Volatile var reason: String? = null
        private set
    private var closed = false

    init {
        try {
            book.adoptLegacy(clock, random)
            tick()
            book.onFixtureClosed = { tick() }
            if (automatic) ticker.scheduleAtFixedRate({ tick() }, 250, 250, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            ticker.shutdown()
            workers.close()
            throw failure
        }
    }

    /** One deterministic dispatch pass; no ledger future is awaited by the ticker. */
    internal fun tick() = lock.withLock {
        if (closed || reason != null) return@withLock
        if (failed()) {
            pause("Ledger failed-stopped; fixture scheduling paused until recovery.")
            return@withLock
        }
        try {
            val now = clock()
            val pending = book.unfinishedFixtures().filter {
                it.managed && !book.isSettling(it.id) && it.result != "LEGACY_UNRESOLVED" &&
                    (it.result != null || it.endsAt <= now)
            }
            // Close admission and record the result before freeing the pool slot.
            for (f in pending) {
                if (f.id in inFlight) continue
                f.lock.writeLock().lock()
                try {
                    if (f.result == null) book.publishResult(f.id, weightedResult(f), notify = false)
                } finally {
                    f.lock.writeLock().unlock()
                }
            }
            for ((sport, count) in Roster.pool) {
                val live = book.openFixtures().count { it.managed && it.sport == sport }
                repeat((count - live).coerceAtLeast(0)) {
                    book.addFixture(random, sport, managed = true, clock = clock)
                }
            }
            for (f in pending) {
                if (!inFlight.add(f.id)) continue
                val result = checkNotNull(f.result)
                try {
                    workers.submit {
                        try {
                            book.settle(f.id, result)
                        } catch (failure: Exception) {
                            lock.withLock { pause("Settlement ${f.id} failed: $failure") }
                        } finally {
                            inFlight.remove(f.id)
                        }
                    }
                } catch (failure: Exception) {
                    inFlight.remove(f.id)
                    throw failure
                }
            }
            lastTick = now
        } catch (failure: Exception) {
            pause("Fixture scheduler failed: $failure")
        }
    }

    private fun pause(message: String) {
        if (reason == null) System.err.println(message)
        reason = message
    }

    /** Odds-implied outcome distribution; an illustrative 1% NFL tie refunds stakes. */
    internal fun weightedResult(f: Book.Fixture): String {
        if (f.sport == Roster.Sport.NFL && random.nextInt(100) == 0) return "VOID"
        var value = random.nextDouble(f.odds.values.sumOf { 1.0 / it })
        for ((code, price) in f.odds) {
            value -= 1.0 / price
            if (value <= 0) return code
        }
        return f.selections.last().code
    }

    /** Scheduler health is separate from simulator state and remains visible when paused. */
    fun view(): Map<String, Any?> = mapOf(
        "state" to if (reason == null) "RUNNING" else "PAUSED",
        "reason" to reason, "lastTick" to lastTick, "settling" to inFlight.size,
    )

    override fun close() {
        lock.withLock {
            closed = true
            book.onFixtureClosed = {}
            ticker.shutdown()
            workers.shutdown()
        }
        check(ticker.awaitTermination(30, TimeUnit.SECONDS)) { "fixture ticker did not stop" }
        // Do not interrupt a writer-dependent operation during reset or Chaos Lab drains.
        workers.close()
    }
}
