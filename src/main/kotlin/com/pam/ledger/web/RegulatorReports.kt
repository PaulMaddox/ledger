package com.pam.ledger.web

import com.pam.ledger.Ledger
import com.pam.ledger.NotFoundException
import com.pam.ledger.SignedTreeHead
import com.pam.ledger.SportsbookWallet.Accounts
import com.pam.ledger.TamperDetectedException
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Supervisory reports over one durable, signed ledger prefix. No live account or
 * read-model balance is mixed into a report, even while bets are being settled.
 * Fixture descriptions are a separately captured, unsigned source. Compliance
 * scenarios are deterministic examples, not evidence of controls being enforced.
 */
internal class RegulatorReports(private val ledger: Ledger, private val book: Book, private val audit: Audit) {
    private class Customer(val id: String, val firstSeq: Long, val firstTime: Long) {
        var openingCash = BigInteger.ZERO
        var openingEscrow = BigInteger.ZERO
        var cash = BigInteger.ZERO
        var escrow = BigInteger.ZERO
        var deposits = BigInteger.ZERO
        var withdrawals = BigInteger.ZERO
        var stakes = BigInteger.ZERO
        var payouts = BigInteger.ZERO
        var revenue = BigInteger.ZERO
        var entries = 0
        var bets = 0
        var lastTime = firstTime
        var lastDeposit: Long? = null
        var lastDepositSeq: Long? = null
    }

    private class Bet(
        val id: String, val player: String, val fixture: String, val selection: String,
        val odds: Int?, val stake: BigInteger, val placedSeq: Long, val placedTime: Long,
    ) {
        var settledSeq: Long? = null
        var settledTime: Long? = null
        var outcome: String? = null
        var payout = BigInteger.ZERO
        var revenue = BigInteger.ZERO
        fun openAt(end: Long) = placedTime < end && (settledTime == null || settledTime!! >= end)
        fun potential() = odds?.takeIf { selection in setOf("HOME", "DRAW", "AWAY") }
            ?.let { stake.multiply(BigInteger.valueOf(it.toLong())).divide(HUNDRED) }
    }

    private fun micros(value: String): Long {
        val instant = try { Instant.parse(value) } catch (e: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("report dates must be ISO-8601 instants with a timezone", e)
        }
        return try {
            Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000), instant.nano / 1_000L)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("report date is outside the supported range", e)
        }
    }

    /** Freeze all report sections together; amount strings are exact minor units. */
    fun create(params: Map<String, String>): Map<String, Any?> {
        val zone = try { ZoneId.of(params["timezone"] ?: "UTC") } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("unknown reporting timezone", e)
        }
        val currency = params["currency"] ?: "EUR"
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be a three-letter uppercase code" }
        val population = params["population"] ?: "all"
        require(population in setOf("all", "manual", "automated")) { "population must be all, manual or automated" }
        fun boundary(instantKey: String, dateKey: String): Long? {
            val date = params[dateKey]?.takeIf { it.isNotBlank() }
            require(date == null || params[instantKey].isNullOrBlank()) { "supply a date or an instant, not both" }
            return if (date != null) {
                val parsed = try { LocalDate.parse(date).atStartOfDay(zone).toInstant() }
                    catch (e: java.time.DateTimeException) { throw IllegalArgumentException("invalid reporting date", e) }
                micros(parsed.toString())
            } else params[instantKey]?.takeIf { it.isNotBlank() }?.let(::micros)
        }
        val requestedFrom = boundary("from", "fromDate")
        val requestedTo = boundary("to", "toDate")
        require(requestedFrom == null || requestedTo == null || requestedFrom < requestedTo) { "start must be before end" }
        // Only the writer creates the checkpoint, and it acknowledges it after fsync.
        // Repeated freezes of unchanged history must not grow the checkpoint journal/cache.
        val anchor = ledger.latestCheckpoint()?.takeIf { it.head.treeSize == ledger.head().size }
            ?: audit.checkpointNow()
        val size = anchor.head.treeSize
        val firstTime = if (size > 0) ledger.entry(0).entry.timestampMicros else anchor.head.timestampMicros
        val lastEntryTime = if (size > 0) ledger.entry(size - 1).entry.timestampMicros else anchor.head.timestampMicros
        val endOfSnapshot = Math.addExact(maxOf(anchor.head.timestampMicros, lastEntryTime), 1)
        val from = requestedFrom ?: firstTime
        val to = requestedTo ?: endOfSnapshot
        require(from < to) { "start must be before end" }
        fun selected(id: String) = population == "all" || Simulator.isAutomated(id) == (population == "automated")
        fun inPeriod(t: Long) = t >= from && t < to

        val customers = linkedMapOf<String, Customer>()
        val bets = linkedMapOf<String, Bet>()
        val days = sortedMapOf<String, MutableMap<String, BigInteger>>()
        val outcomes = linkedMapOf<String, Int>()
        val signals = arrayListOf<Map<String, Any?>>()
        var conservation = BigInteger.ZERO
        var unclassified = 0
        var otherEntries = 0
        var periodEntries = 0
        var returnedStakes = BigInteger.ZERO
        var voidRefunds = BigInteger.ZERO
        var cashOuts = BigInteger.ZERO
        var winPayouts = BigInteger.ZERO
        for (seq in 0 until size) {
            val e = ledger.entry(seq).entry
            val postings = e.postings.filter { it.currency == currency }
            conservation += postings.fold(BigInteger.ZERO) { total, p -> total + p.amount.toBigInteger() }
            if (postings.isEmpty()) continue
            val player = e.metadata["playerId"]
            if (player == null) {
                if (inPeriod(e.timestampMicros)) unclassified++
                continue
            }
            if (!selected(player) || e.timestampMicros >= to) continue
            val c = customers.getOrPut(player) { Customer(player, seq, e.timestampMicros) }
            c.lastTime = e.timestampMicros
            val cash = postings.filter { it.account == Accounts.cash(player, currency) }
                .fold(BigInteger.ZERO) { total, p -> total + p.amount.toBigInteger() }
            val escrow = postings.filter { it.account == Accounts.openBets(player, currency) }
                .fold(BigInteger.ZERO) { total, p -> total + p.amount.toBigInteger() }
            val revenue = postings.filter { it.account == Accounts.revenue(currency) }
                .fold(BigInteger.ZERO) { total, p -> total + p.amount.toBigInteger() }
            c.cash += cash
            c.escrow += escrow
            if (e.timestampMicros < from) {
                c.openingCash += cash
                c.openingEscrow += escrow
            }
            val included = inPeriod(e.timestampMicros)
            if (included) {
                periodEntries++
                c.entries++
                c.revenue += revenue
            }
            fun dayAmount(key: String, amount: BigInteger) {
                if (included) {
                    val day = instant(e.timestampMicros).atZone(zone).toLocalDate().toString()
                    val row = days.getOrPut(day) { linkedMapOf() }
                    row[key] = row.getOrDefault(key, BigInteger.ZERO) + amount
                }
            }
            dayAmount("revenue", revenue)
            when (e.kind) {
                "DEPOSIT" -> {
                    c.lastDeposit = e.timestampMicros
                    c.lastDepositSeq = seq
                    if (included) c.deposits += cash
                    dayAmount("deposits", cash)
                }
                "WITHDRAWAL" -> {
                    if (included) {
                        c.withdrawals -= cash
                        val depositTime = c.lastDeposit
                        if (depositTime != null && e.timestampMicros - depositTime <= 600_000_000L) {
                            signals += signal("payment-review", player, seq, c.lastDepositSeq,
                                "Withdrawal within 10 minutes of a deposit",
                                "Illustrative monitoring rule; legitimate withdrawals can trigger it.")
                        }
                    }
                    dayAmount("withdrawals", -cash)
                }
                "BET_PLACED" -> {
                    val id = e.metadata.getValue("betId")
                    val parts = e.metadata["selection"]?.split("|").orEmpty()
                    val attributed = parts.size == 3 && parts[2].toIntOrNull()?.let { it >= 0 } == true
                    bets[id] = Bet(id, player, if (attributed) parts[0] else "UNATTRIBUTED",
                        if (attributed) parts[1] else "Unknown", if (attributed) parts[2].toInt() else null,
                        escrow, seq, e.timestampMicros)
                    if (included) {
                        c.stakes += escrow
                        c.bets++
                    }
                    dayAmount("stakes", escrow)
                }
                "BET_WON", "BET_LOST", "BET_VOIDED", "BET_CASHED_OUT" -> {
                    val b = bets[e.metadata["betId"]]
                        ?: throw TamperDetectedException("report cannot attribute settlement #$seq to its placement")
                    b.settledSeq = seq
                    b.settledTime = e.timestampMicros
                    b.outcome = e.kind.removePrefix("BET_")
                    b.payout = cash
                    b.revenue = revenue
                    if (included) {
                        c.payouts += cash
                        returnedStakes -= escrow
                        outcomes.merge(b.outcome!!, 1, Int::plus)
                        when (e.kind) {
                            "BET_WON" -> winPayouts += cash
                            "BET_VOIDED" -> voidRefunds += cash
                            "BET_CASHED_OUT" -> cashOuts += cash
                        }
                    }
                    dayAmount("payouts", cash)
                }
                else -> if (included) otherEntries++
            }
        }
        fun sum(value: (Customer) -> BigInteger) = customers.values.fold(BigInteger.ZERO) { total, c -> total + value(c) }
        val deposits = sum { it.deposits }
        val withdrawals = sum { it.withdrawals }
        val stakes = sum { it.stakes }
        val payouts = sum { it.payouts }
        val revenue = sum { it.revenue }
        val opening = sum { it.openingCash + it.openingEscrow }
        val closing = sum { it.cash + it.escrow }
        val otherMovement = closing - opening - deposits + withdrawals + revenue
        val open = bets.values.filter { it.openAt(to) }
        val openStakes = open.fold(BigInteger.ZERO) { total, b -> total + b.stake }
        val active = customers.values.filter { it.entries > 0 }
        active.filter { it.bets >= 20 }.forEach { c ->
            signals += signal("activity-review", c.id, c.firstSeq, null, "20 or more bets in the reporting period",
                "Illustrative activity threshold, not a diagnosis or a prescribed regulatory limit.")
        }
        val fixtureDefinitions = book.fixtures().associate { f ->
            f.id to mapOf("home" to f.home, "away" to f.away, "declaredResult" to f.result,
                "declaredComplete" to f.settlementComplete)
        }
        val betsByFixture = bets.values.groupBy { it.fixture }
        val fixtureRows = (betsByFixture.keys + if (population == "all") fixtureDefinitions.keys else emptySet())
            .distinct().sorted().map { id ->
                val all = betsByFixture[id].orEmpty()
                val placed = all.filter { inPeriod(it.placedTime) }
                val settled = all.filter { it.settledTime?.let(::inPeriod) == true }
                val outstanding = all.filter { it.openAt(to) }
                val liability = outstanding.groupBy { it.selection }.mapValues { (_, group) ->
                    if (group.any { it.potential() == null }) null
                    else group.fold(BigInteger.ZERO) { total, b -> total + b.potential()!! }.toString()
                }
                val definition = fixtureDefinitions[id]
                val exceptions = arrayListOf<String>()
                if (definition == null) exceptions += "Fixture definition unavailable"
                if (definition?.get("declaredResult") != null && outstanding.isNotEmpty()) {
                    exceptions += "Declared result with outstanding bets (may be settling or outside the report cutoff)"
                }
                val worst = if (liability.values.any { it == null }) null
                    else liability.values.filterNotNull().maxOfOrNull { it.toBigInteger() } ?: BigInteger.ZERO
                mapOf("id" to id, "definition" to definition, "placedBets" to placed.size,
                    "bettors" to placed.map { it.player }.distinct().size,
                    "stakes" to placed.fold(BigInteger.ZERO) { total, b -> total + b.stake }.toString(),
                    "settlements" to settled.size, "outcomes" to settled.groupingBy { it.outcome }.eachCount(),
                    "payouts" to settled.fold(BigInteger.ZERO) { total, b -> total + b.payout }.toString(),
                    "revenue" to settled.fold(BigInteger.ZERO) { total, b -> total + b.revenue }.toString(),
                    "openBets" to outstanding.size,
                    "openStakes" to outstanding.fold(BigInteger.ZERO) { total, b -> total + b.stake }.toString(),
                    "liabilityBySelection" to liability, "worstWinningPayout" to worst?.toString(),
                    "exceptions" to exceptions,
                    "entrySeqs" to (placed.map { it.placedSeq } + settled.mapNotNull { it.settledSeq }).sorted())
            }
        val customerRows = customers.values.sortedBy { it.id }.map { c ->
            mapOf("id" to c.id, "automated" to Simulator.isAutomated(c.id),
                "firstSeq" to c.firstSeq, "firstActivity" to iso(c.firstTime), "lastActivity" to iso(c.lastTime),
                "entries" to c.entries, "bets" to c.bets, "deposits" to c.deposits.toString(),
                "withdrawals" to c.withdrawals.toString(), "stakes" to c.stakes.toString(),
                "payouts" to c.payouts.toString(), "netSettledLoss" to c.revenue.toString(),
                "openingCash" to c.openingCash.toString(), "cash" to c.cash.toString(),
                "openingEscrow" to c.openingEscrow.toString(), "escrow" to c.escrow.toString(),
                "activityShareBps" to if (stakes.signum() > 0) (c.stakes * TEN_THOUSAND / stakes).toString() else null)
        }
        val definitions = linkedMapOf(
            "deposits" to "Committed customer cash credits classified DEPOSIT in [from, to).",
            "withdrawals" to "Committed customer cash debits classified WITHDRAWAL in [from, to).",
            "stakes" to "Placement-period stakes; includes bets not yet settled.",
            "payouts" to "Settlement-period returns, including stake, void refunds and cash-outs.",
            "revenue" to "Settlement-period house revenue postings, not stakes minus unrelated period payouts; not a jurisdiction-specific tax return.",
            "customerLiability" to "Replayed cash plus escrow at the cutoff, not proof of bank funds or safeguarding.",
            "activeCustomers" to "Distinct customers with any recorded transaction in the period; not registrations.",
            "worstWinningPayout" to "Largest HOME/DRAW/AWAY winning payout for outstanding single-selection bets; VOID refunds and unknown markets are separate.",
        )
        return linkedMapOf(
            "manifest" to linkedMapOf(
                "version" to "supervisory-report-v1", "ledgerId" to anchor.head.ledgerId,
                "dataset" to if (size > 0) ledger.entry(0).chainHash.toHex() else anchor.head.rootHash.toHex(),
                "generatedAt" to iso(anchor.head.timestampMicros), "from" to iso(from), "to" to iso(to),
                "timezone" to zone.id, "currency" to currency, "population" to population,
                "availableFrom" to iso(firstTime), "availableThrough" to iso(endOfSnapshot),
                "coverage" to if (from >= firstTime && to <= endOfSnapshot) "Available demo history" else "Partial period",
                "historyNote" to "Demo history resets daily. No report proves activity before the retained history.",
                "sourceNotes" to listOf("Financial and activity figures: signed ledger prefix.",
                    "Fixture descriptions and declared results: unsigned snapshot captured separately.",
                    "Compliance scenarios: deterministic synthetic examples; no controls are enforced by these reports."),
                "definitions" to definitions, "anchor" to anchorView(anchor), "publicKey" to ledger.publicKey.encoded),
            "financial" to linkedMapOf("openingLiability" to opening.toString(), "closingLiability" to closing.toString(),
                "cashLiability" to sum { it.cash }.toString(), "escrowLiability" to sum { it.escrow }.toString(),
                "deposits" to deposits.toString(), "withdrawals" to withdrawals.toString(), "stakes" to stakes.toString(),
                "payouts" to payouts.toString(), "revenue" to revenue.toString(),
                "winPayouts" to winPayouts.toString(), "voidRefunds" to voidRefunds.toString(), "cashOuts" to cashOuts.toString(),
                "settledStakes" to returnedStakes.toString(), "otherMovement" to otherMovement.toString(),
                "conservation" to conservation.toString(), "openBets" to open.size, "openStakes" to openStakes.toString(),
                "escrowReconciles" to (openStakes == sum { it.escrow }), "unclassifiedEntries" to unclassified,
                "otherEntries" to otherEntries, "periodEntries" to periodEntries, "outcomes" to outcomes,
                "bankReconciliation" to null, "tax" to null),
            "customers" to customerRows, "fixtures" to fixtureRows,
            "customerSummary" to mapOf("observed" to customers.size, "active" to active.size,
                "bettors" to active.count { it.bets > 0 }, "depositors" to active.count { it.deposits.signum() > 0 },
                "firstActivityInPeriod" to active.count { inPeriod(it.firstTime) },
                "registrations" to null, "verified" to null, "excluded" to null),
            "daily" to days.map { (day, values) -> mapOf("day" to day) +
                listOf("deposits", "withdrawals", "stakes", "payouts", "revenue").associateWith { values.getOrDefault(it, BigInteger.ZERO).toString() } },
            "signals" to signals,
            "compliance" to scenarios(),
            "coverage" to listOf(
                coverage("Money and betting activity", "Ledger-derived", "Exact amounts, period flows, liabilities and transaction drill-down."),
                coverage("Fixture catalogue", "Unsigned snapshot", "Descriptions/results are not authenticated by the ledger signature; sport and start time are not collected."),
                coverage("Customer eligibility", "Not collected", "Registration, age, identity, location, sanctions and due-diligence decisions."),
                coverage("Player protection", "Not collected", "Real limits, exclusions, blocked attempts, sessions, interventions and outcomes."),
                coverage("Cases and governance", "Synthetic examples", "Example case timelines, control owners, training and audit findings; not live compliance evidence."),
                coverage("External financial reconciliation", "Not collected", "Bank balances, payment-provider statements, safeguarding and taxes."),
                coverage("Monitoring", "Illustrative rules", "Explainable transaction signals requiring human review, not determinations of harm or crime."),
            ),
        )
    }

    /** Bounded, replayable canonical evidence for a report, rejected after a reset. */
    fun evidence(params: Map<String, String>): Map<String, Any?> {
        val anchor = reportAnchor(params)
        val size = anchor.head.treeSize
        val offset = params["offset"]?.toLongOrNull() ?: if (params.containsKey("offset")) {
            throw IllegalArgumentException("invalid evidence offset")
        } else 0L
        val limit = params["limit"]?.toIntOrNull() ?: if (params.containsKey("limit")) {
            throw IllegalArgumentException("invalid evidence limit")
        } else 100
        require(offset in 0..size && limit in 1..1_000) { "offset must be within the snapshot and limit must be 1-1000" }
        val player = params["player"]?.takeIf { it.isNotBlank() }
        val fixture = params["fixture"]?.takeIf { it.isNotBlank() }
        val rows = arrayListOf<Map<String, Any?>>()
        var next = offset
        val scanEnd = minOf(size, offset + 1_000)
        while (next < scanEnd && rows.size < limit) {
            val sealed = ledger.entry(next++)
            val e = sealed.entry
            if (player != null && e.metadata["playerId"] != player) continue
            if (fixture != null) {
                val placement = if (e.kind == "BET_PLACED") sealed else e.metadata["placementSeq"]?.toLongOrNull()
                    ?.takeIf { it in 0 until size }?.let(ledger::entry)
                if (placement == null) continue
                val parts = placement.entry.metadata["selection"]?.split("|").orEmpty()
                val id = if (parts.size == 3 && parts[2].toIntOrNull()?.let { it >= 0 } == true) parts[0] else "UNATTRIBUTED"
                if (id != fixture) continue
            }
            rows += mapOf("seq" to e.seq, "kind" to e.kind, "time" to iso(e.timestampMicros),
                "player" to e.metadata["playerId"], "key" to e.idempotencyKey,
                "postings" to e.postings.map { mapOf("account" to it.account.value, "currency" to it.currency, "amount" to it.amount.toString()) },
                "metadata" to e.metadata, "entryBytes" to sealed.encodedBytes(),
                "chainHash" to sealed.chainHash, "leafHash" to sealed.leafHash)
        }
        return mapOf("anchor" to anchorView(anchor), "entries" to rows, "nextOffset" to next, "complete" to (next == size))
    }

    /** Inclusion against the report's own checkpoint, never a different post-reset history. */
    fun proof(params: Map<String, String>): Map<String, Any?> {
        val anchor = reportAnchor(params)
        val seq = params["seq"]?.toLongOrNull() ?: throw IllegalArgumentException("missing or invalid seq")
        require(seq in 0 until anchor.head.treeSize) { "entry is outside the report checkpoint" }
        val sealed = ledger.entry(seq)
        val proof = ledger.inclusionProof(seq, anchor.head.treeSize)
        return mapOf("entryBytes" to sealed.encodedBytes(), "leafHash" to proof.leafHash,
            "entry" to mapOf("seq" to seq), "path" to proof.auditPath, "treeSize" to anchor.head.treeSize,
            "sth" to (anchorView(anchor) + ("verified" to anchor.verify(ledger.publicKey))),
            "ledgerId" to anchor.head.ledgerId, "publicKey" to ledger.publicKey.encoded)
    }

    private fun reportAnchor(params: Map<String, String>): SignedTreeHead {
        val size = params["size"]?.toLongOrNull() ?: throw IllegalArgumentException("missing or invalid size")
        require(size >= 0) { "size must be non-negative" }
        val root = params["root"] ?: throw IllegalArgumentException("missing root")
        require(root.matches(Regex("[a-f0-9]{64}"))) { "root must be a SHA-256 hex digest" }
        return audit.checkpointOfSize(size)?.takeIf { it.head.rootHash.toHex() == root }
            ?: throw NotFoundException("report snapshot is no longer available; freeze a new report after a demo reset")
    }

    private fun scenarios(): Map<String, Any?> = mapOf(
        "version" to "synthetic-compliance-v1",
        "note" to "Separate fictional scenarios, not customer records or an operational compliance log. Ordered events are regenerated deterministically; they do not restrict any live demo account.",
        "cases" to listOf(
            case("RG-001", "Player protection", "Follow-up due", "Demo safer-play lead",
                listOf("Deposit-limit request received", "Limit acknowledged", "Illustrative blocked deposit", "Support interaction recorded", "Effectiveness follow-up due")),
            case("RG-002", "Self-exclusion", "Closed example", "Demo safer-play lead",
                listOf("Exclusion requested", "Access restriction acknowledged", "Marketing suppression acknowledged", "Illustrative blocked login", "Exclusion checks reviewed")),
            case("FCP-001", "Payment review", "Review due", "Demo financial-crime lead",
                listOf("Monitoring alert opened", "Due-diligence evidence requested", "Enhanced review assigned", "Escalation decision pending")),
            case("CMP-001", "Settlement dispute", "Resolved example", "Demo complaints lead",
                listOf("Complaint received", "Placement and settlement entries inspected", "Outcome explained to customer", "Resolution recorded")),
        ),
        "controls" to listOf(
            mapOf("control" to "Player-protection programme", "owner" to "Demo safer-play lead", "status" to "Illustrative review due", "evidence" to "Example programme review and intervention evaluation"),
            mapOf("control" to "Staff training", "owner" to "Demo compliance lead", "status" to "Illustrative overdue module", "evidence" to "Example training completion register"),
            mapOf("control" to "Responsible marketing", "owner" to "Demo marketing lead", "status" to "Illustrative check recorded", "evidence" to "Example age/vulnerability targeting and exclusion-suppression checks"),
            mapOf("control" to "Independent programme audit", "owner" to "Demo compliance lead", "status" to "No real audit recorded", "evidence" to "Would require dated scope, auditor, findings and corrective-action evidence"),
        ),
    )

    private fun case(id: String, category: String, status: String, owner: String, events: List<String>) = mapOf(
        "id" to id, "category" to category, "status" to status, "owner" to owner, "source" to "Synthetic example",
        "events" to events.mapIndexed { index, text -> mapOf("id" to "$id:${index + 1}", "order" to index + 1, "event" to text) },
    )
    private fun coverage(area: String, status: String, detail: String) = mapOf("area" to area, "status" to status, "detail" to detail)
    private fun signal(type: String, player: String, seq: Long, related: Long?, title: String, explanation: String) =
        mapOf("id" to "$type:$player:$seq", "type" to type, "player" to player, "seq" to seq, "relatedSeq" to related,
            "title" to title, "explanation" to explanation, "status" to "Unreviewed indicator")

    companion object {
        private val HUNDRED = BigInteger.valueOf(100)
        private val TEN_THOUSAND = BigInteger.valueOf(10_000)
        private fun instant(micros: Long) = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
        private fun iso(micros: Long) = instant(micros).toString()
        private fun anchorView(s: SignedTreeHead) = mapOf("treeSize" to s.head.treeSize, "rootHash" to s.head.rootHash,
            "chainHead" to s.head.chainHead, "ledgerId" to s.head.ledgerId, "keyId" to s.keyId,
            "signature" to s.signature(), "signingBytes" to s.head.signingBytes())
    }
}
