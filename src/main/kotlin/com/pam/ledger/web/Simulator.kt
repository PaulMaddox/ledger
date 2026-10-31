package com.pam.ledger.web

import com.pam.ledger.InsufficientFundsException
import com.pam.ledger.LedgerException
import com.pam.ledger.SportsbookWallet
import com.pam.ledger.SportsbookWallet.Accounts
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/*
 * ============================================================================
 *  SIMULATOR: automated customers generating constant traffic
 * ============================================================================
 *
 *  A run has a number of automated customers (sim-00001, sim-00002, ...), each
 *  performing [opsPerMinute] operations a minute: mostly bets, plus deposits,
 *  withdrawals and cash-outs. Every 100 ms the simulator launches the operations
 *  due in that slice, each on its own virtual thread.
 *
 *  Automated customers are ordinary ledger players. They go through exactly the
 *  same Book and SportsbookWallet calls as the customer screen, so everything they
 *  do appears in the customer, operator and regulator views. Rejections such as
 *  "insufficient funds" are expected and counted: they deliberately overspend.
 *
 *  Lifecycle: STOPPED -> start() -> RUNNING <-> pause()/resume() <-> PAUSED,
 *  and stop() from either. Pausing keeps the run and its counters; stopping ends
 *  the run, and the next start() begins with fresh counters. Customers keep their
 *  money and history between runs: the ledger never forgets.
 *
 *  At most 512 operations are in flight. Beyond that, operations are shed (and
 *  counted) rather than queued, so an excessive setting cannot exhaust memory.
 */
class Simulator(private val book: Book, private val wallet: SportsbookWallet) : AutoCloseable {

    enum class State { STOPPED, RUNNING, PAUSED }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var customers = 500
        private set
    @Volatile var opsPerMinute = 60
        private set
    @Volatile var autoFixtures = true
        private set
    @Volatile var startedAtMillis = 0L
        private set

    private class Run {
        val bets = AtomicLong(); val deposits = AtomicLong(); val withdrawals = AtomicLong(); val cashOuts = AtomicLong()
        val rejectedForFunds = AtomicLong(); val rejectedOther = AtomicLong(); val shed = AtomicLong()
        val fixtureRounds = AtomicLong()
    }
    @Volatile private var run = Run()
    val bets get() = run.bets
    val deposits get() = run.deposits
    val withdrawals get() = run.withdrawals
    val cashOuts get() = run.cashOuts
    val rejectedForFunds get() = run.rejectedForFunds
    val rejectedOther get() = run.rejectedOther
    val shed get() = run.shed
    val fixtureRounds get() = run.fixtureRounds
    val succeeded get() = run.let { it.bets.get() + it.deposits.get() + it.withdrawals.get() + it.cashOuts.get() }

    /**
     * One automated customer's action, for the live wall on the Simulator tab.
     * [cash] is the customer's cash balance read right after the action.
     */
    data class Event(val id: Long, val time: Long, val customer: String, val op: String, val text: String, val ok: Boolean, val cash: Long)
    data class EventPage(val lastId: Long, val events: List<Event>)

    private val events = ArrayDeque<Event>()
    private val eventIds = AtomicLong()

    private fun record(counters: Run, customer: String, op: String, text: String, ok: Boolean) {
        val cash = wallet.cashBalance(customer, "EUR")
        synchronized(events) {
            if (counters !== run) return
            val e = Event(eventIds.incrementAndGet(), System.currentTimeMillis(), customer, op, text, ok, cash)
            events.addLast(e)
            if (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** A stable page of events newer than [afterId], in publication order. */
    fun eventPage(afterId: Long, limit: Int = 2_000): EventPage = synchronized(events) {
        val page = events.filter { it.id > afterId }.take(limit)
        EventPage(page.lastOrNull()?.id ?: (events.lastOrNull()?.id ?: 0L), page)
    }

    val lastEventId get() = eventIds.get()

    private val funded = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = Semaphore(MAX_IN_FLIGHT)
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "simulator").apply { isDaemon = true } }
    private var carry = 0.0
    private var lastRoundNanos = System.nanoTime()

    init {
        ticker.scheduleAtFixedRate({ runCatching { tick() } }, 100, 100, TimeUnit.MILLISECONDS)
    }

    /** Change the run's shape. Allowed at any time; a running simulation adapts on its next tick. */
    fun configure(customers: Int, opsPerMinute: Int, autoFixtures: Boolean) {
        require(customers in 1..MAX_CUSTOMERS) { "customers must be between 1 and $MAX_CUSTOMERS" }
        require(opsPerMinute in 1..600) { "operations per customer per minute must be between 1 and 600" }
        this.customers = customers
        this.opsPerMinute = opsPerMinute
        this.autoFixtures = autoFixtures
    }

    @Synchronized fun start() {
        if (state != State.STOPPED) return
        synchronized(events) { run = Run(); events.clear() }
        carry = 0.0
        startedAtMillis = System.currentTimeMillis()
        lastRoundNanos = System.nanoTime()
        state = State.RUNNING
    }

    @Synchronized fun pause() { if (state == State.RUNNING) state = State.PAUSED }
    @Synchronized fun resume() { if (state == State.PAUSED) state = State.RUNNING }
    @Synchronized fun stop() { state = State.STOPPED }

    /** Operations per second the current settings ask for. */
    val targetRate get() = customers * opsPerMinute / 60.0

    @Synchronized private fun tick() {
        if (state != State.RUNNING) return
        val counters = run
        carry += targetRate / 10.0
        val due = carry.toInt()
        carry -= due
        repeat(due) {
            if (!inFlight.tryAcquire()) { counters.shed.incrementAndGet(); return@repeat }
            workers.submit { try { operation(Random, counters) } finally { inFlight.release() } }
        }
        // Keep the book moving: settle the oldest open fixture and open a new one every 15 s.
        if (autoFixtures && System.nanoTime() - lastRoundNanos > 15_000_000_000L) {
            lastRoundNanos = System.nanoTime()
            book.fixtures().firstOrNull { it.isOpen }?.let { f ->
                workers.submit { runCatching { book.settle(f.id, weightedResult(f)); counters.fixtureRounds.incrementAndGet() } }
            }
            book.addFixture()
        }
    }

    /** A result drawn with the probabilities the odds imply, so revenue behaves realistically. */
    private fun weightedResult(f: Book.Fixture): String {
        val weights = f.odds.mapValues { 1.0 / it.value }
        var r = Random.nextDouble(weights.values.sum())
        for ((k, w) in weights) { r -= w; if (r <= 0) return k }
        return "HOME"
    }

    private fun operation(rnd: Random, counters: Run) {
        val customer = customerName(rnd.nextInt(customers) + 1)
        var op = "SEED"
        var text = ""
        try {
            // Each automated customer starts with a deposit of EUR 50 to EUR 500 the first time it acts.
            if (!funded.contains(customer)) {
                val ref = "sim-seed-$customer"
                if (!wallet.hasDeposit(ref)) {
                    // A stable payload survives retries and restarts; legacy random seeds
                    // are recognized by their committed reference, not regenerated.
                    val amount = 50_00 + (customer.hashCode().toLong() and 0xffffffffL) % 450_00
                    text = "joined, deposited ${eur(amount)}"
                    wallet.deposit(customer, amount, "EUR", ref)
                    if (funded.add(customer)) record(counters, customer, op, text, true)
                } else funded.add(customer)
            }
            when (rnd.nextInt(100)) {
                in 0..64 -> {
                    op = "BET"
                    val f = book.fixtures().filter { it.isOpen }.randomOrNull(rnd) ?: return
                    val selection = listOf("HOME", "DRAW", "AWAY").random(rnd)
                    val stake = rnd.nextLong(1_00, 40_00)
                    val odds = f.odds.getValue(selection)
                    text = "bet ${eur(stake)} on ${f.id} $selection @ ${"%.2f".format(Locale.ROOT, odds / 100.0)}"
                    val betId = "sim-" + UUID.randomUUID()
                    book.placeBet(customer, f.id, selection, stake, betId)
                    counters.bets.incrementAndGet()
                }
                in 65..79 -> {
                    op = "DEPOSIT"
                    val amount = rnd.nextLong(10_00, 100_00)
                    text = "deposited ${eur(amount)}"
                    wallet.deposit(customer, amount, "EUR", "sim-dep-" + UUID.randomUUID())
                    counters.deposits.incrementAndGet()
                }
                in 80..89 -> {
                    op = "WITHDRAW"
                    // Deliberately sometimes more than the balance, to show the ledger refusing.
                    val amount = rnd.nextLong(5_00, 200_00)
                    text = "withdrew ${eur(amount)}"
                    wallet.withdraw(customer, amount, "EUR", "sim-wd-" + UUID.randomUUID())
                    counters.withdrawals.incrementAndGet()
                }
                else -> {
                    op = "CASH_OUT"
                    val bet = book.openBetsForPlayer(customer).randomOrNull(rnd) ?: return
                    val offer = book.cashOutOffer(bet.betId)
                    text = "cashed out ${eur(offer)}"
                    book.cashOut(bet.betId)
                    counters.cashOuts.incrementAndGet()
                }
            }
            record(counters, customer, op, text, true)
        } catch (e: InsufficientFundsException) {
            counters.rejectedForFunds.incrementAndGet()
            record(counters, customer, op, "$text: refused, insufficient funds", false)
        } catch (e: LedgerException) {
            // e.g. a cash-out losing the race to settlement: refused, as it should be
            counters.rejectedOther.incrementAndGet()
            record(counters, customer, op, "$text: refused, " + (if (e is com.pam.ledger.IdempotencyConflictException) "bet already settled" else (e.message ?: "rejected")), false)
        } catch (e: IllegalArgumentException) {
            counters.rejectedOther.incrementAndGet() // e.g. the fixture was settled a moment ago
            record(counters, customer, op, "$text: refused, ${e.message}", false)
        }
    }

    // Locale.ROOT: always "12.40", never "12,40", whatever the machine's regional settings.
    private fun eur(minor: Long) = "€%.2f".format(Locale.ROOT, minor / 100.0)

    override fun close() {
        state = State.STOPPED
        ticker.shutdownNow()
        workers.shutdown()
        workers.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val MAX_CUSTOMERS = 20_000
        private const val MAX_IN_FLIGHT = 512
        private const val MAX_EVENTS = 5_000
        fun customerName(i: Int) = "sim-%05d".format(i)
        fun isAutomated(player: String) = player.startsWith("sim-")
    }
}

/** Samples headline numbers once a second for the operator's charts (last 3 minutes). */
class Stats(private val readModel: ReadModel) : AutoCloseable {
    data class Point(val t: Long, val entries: Long, val txPerSec: Long, val ggr: Long, val exposure: Long, val openBets: Int)

    private val points = ArrayDeque<Point>()
    private val sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stats").apply { isDaemon = true } }

    init {
        sampler.scheduleAtFixedRate({ runCatching { sample() } }, 0, 1, TimeUnit.SECONDS)
    }

    private fun sample() {
        val totals = readModel.totals()
        val open = readModel.openBets()
        val prev = synchronized(points) { points.lastOrNull() }
        val p = Point(
            t = System.currentTimeMillis(),
            entries = totals.entries,
            txPerSec = if (prev == null) 0 else totals.entries - prev.entries,
            ggr = readModel.balance(Accounts.revenue("EUR")),
            exposure = open.sumOf { it.potentialPayout - it.stake },
            openBets = open.size,
        )
        synchronized(points) {
            points.addLast(p)
            if (points.size > 180) points.removeFirst()
        }
    }

    fun series(): List<Point> = synchronized(points) { points.toList() }

    override fun close() { sampler.shutdownNow() }
}
