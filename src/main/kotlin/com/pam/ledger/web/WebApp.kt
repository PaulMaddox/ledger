package com.pam.ledger.web

import com.pam.ledger.AmountOverflowException
import com.pam.ledger.AccountState
import com.pam.ledger.CurrencyMismatchException
import com.pam.ledger.Ed25519TreeHeadSigner
import com.pam.ledger.IdempotencyConflictException
import com.pam.ledger.InsufficientFundsException
import com.pam.ledger.Ledger
import com.pam.ledger.LedgerClosedException
import com.pam.ledger.LedgerConfig
import com.pam.ledger.LedgerFailedException
import com.pam.ledger.LedgerVerifier
import com.pam.ledger.MerkleVerify
import com.pam.ledger.NotFoundException
import com.pam.ledger.OutcomeUnknownException
import com.pam.ledger.OverloadedException
import com.pam.ledger.Receipt
import com.pam.ledger.SealedEntry
import com.pam.ledger.SignedTreeHead
import com.pam.ledger.SportsbookWallet
import com.pam.ledger.SportsbookWallet.Accounts
import com.pam.ledger.TamperDetectedException
import com.pam.ledger.VersionConflictException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigInteger
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/*
 * ============================================================================
 *  LEDGER WEB APP: dashboards for customers, operators, regulators and a simulator
 * ============================================================================
 *
 *  Run with:  ./gradlew web          (Windows: .\gradlew.bat web)
 *  Options:   --port 8080  --host 127.0.0.1  --allowed-host <hostname>  --data <folder>
 *
 *  Uses only the JDK's built-in HTTP server, so the runtime still has no
 *  dependencies beyond the JDK and the Kotlin standard library.
 *
 *  THIS IS A DEMONSTRATION. There is no authentication: the persona tabs are a
 *  view switch, not access control. It therefore listens on localhost only
 *  unless --host says otherwise.
 */

/** Bind address and additional exact request hosts; wildcard binding never permits arbitrary Host headers. */
data class WebOptions(
    val port: Int = 8080,
    val host: String = "127.0.0.1",
    val dataDir: Path? = null,
    val allowedHosts: Set<String> = emptySet(),
)

private fun allowedHost(value: String): String {
    val authority = if (':' in value && !value.startsWith("[")) "[$value]" else value
    val uri = try { URI("http://$authority") } catch (e: java.net.URISyntaxException) {
        throw IllegalArgumentException("--allowed-host must be a hostname or IP address without a port", e)
    }
    val host = uri.host?.removeSurrounding("[", "]")?.lowercase()
    require(host != null && host !in setOf("0.0.0.0", "::") && uri.port == -1 &&
        uri.rawUserInfo == null && uri.rawPath.isEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
        "--allowed-host must be a hostname or IP address without a port, not a wildcard"
    }
    return host
}

internal fun WebOptions.allowedRequestHosts(): Set<String> {
    val configured = host.removeSurrounding("[", "]").lowercase()
    val loopbacks = setOf("localhost", "127.0.0.1", "::1")
    val defaults = if (configured in loopbacks || configured in setOf("0.0.0.0", "::")) loopbacks else setOf(configured)
    return defaults + allowedHosts.map(::allowedHost)
}

fun main(args: Array<String>) {
    val opts = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println("usage: web [--port 8080] [--host 127.0.0.1] [--allowed-host <hostname>] [--data <folder>]")
        exitProcess(2)
    }
    val app = LedgerWebApp.start(opts)
    Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    println()
    println("  Ledger web app running at  http://${if (opts.host == "0.0.0.0") "localhost" else opts.host}:${app.port}/")
    println("  Ledger files in            ${app.directory}")
    println("  Press Ctrl+C to stop.")
    if (opts.host != "127.0.0.1" && opts.host != "localhost") {
        println("  WARNING: listening on ${opts.host}. There is no authentication; anyone who can reach it can move money.")
    }
    Thread.currentThread().join() // serve until interrupted
}

internal fun parseArgs(args: Array<String>): WebOptions {
    var o = WebOptions()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("missing value for ${args[i - 1]}")
    while (i < args.size) {
        when (args[i]) {
            "--port" -> o = o.copy(port = value().toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("--port must be 0-65535"))
            "--host" -> o = o.copy(host = value())
            "--allowed-host" -> o = o.copy(allowedHosts = o.allowedHosts + allowedHost(value()))
            "--data" -> o = o.copy(dataDir = Path.of(value()))
            else -> throw IllegalArgumentException("unknown option ${args[i]}")
        }
        i++
    }
    return o
}

private fun minorUnits(amount: Long) = amount.toString()
private fun minorUnits(amount: BigInteger) = amount.toString()
private fun time(micros: Long) = Instant.ofEpochMilli(micros / 1000).toString()

/** Reconcile each immutable engine account snapshot with its own posting prefix. */
internal fun replayPlayerAudit(
    ledger: Ledger,
    id: String,
    cashState: AccountState?,
    escrowState: AccountState?,
    committedSize: Long,
): Map<String, Any?> {
    val cashAccount = Accounts.cash(id, "EUR")
    val escrowAccount = Accounts.openBets(id, "EUR")
    val cashLastSeq = cashState?.lastSeq ?: (committedSize - 1)
    val escrowLastSeq = escrowState?.lastSeq ?: (committedSize - 1)
    var cash = 0L
    var escrow = 0L
    var neverNegative = true
    // Entries precede account states. A missing state must still reconcile with the
    // previously captured committed prefix, not silently discard that account's history.
    val rows = (0L..maxOf(cashLastSeq, escrowLastSeq)).mapNotNull { seq ->
        val s = ledger.entry(seq)
        val cashPostings = s.entry.postings.filter { seq <= cashLastSeq && it.account == cashAccount }
        val escrowPostings = s.entry.postings.filter { seq <= escrowLastSeq && it.account == escrowAccount }
        if (cashPostings.isEmpty() && escrowPostings.isEmpty()) return@mapNotNull null
        val dc = cashPostings.fold(0L) { total, posting -> Math.addExact(total, posting.amount) }
        val de = escrowPostings.fold(0L) { total, posting -> Math.addExact(total, posting.amount) }
        cash = Math.addExact(cash, dc)
        escrow = Math.addExact(escrow, de)
        neverNegative = neverNegative && cash >= 0 && escrow >= 0
        mapOf("seq" to seq, "kind" to s.entry.kind, "time" to time(s.entry.timestampMicros), "key" to s.entry.idempotencyKey,
            "cashDelta" to minorUnits(dc), "cash" to minorUnits(cash), "escrowDelta" to minorUnits(de),
            "escrow" to minorUnits(escrow), "chainHash" to s.chainHash)
    }
    val ledgerCash = cashState?.balance ?: 0L
    val ledgerEscrow = escrowState?.balance ?: 0L
    return mapOf(
        "id" to id, "rows" to rows.asReversed().take(200), "entries" to rows.size,
        "replayedCash" to minorUnits(cash), "replayedEscrow" to minorUnits(escrow),
        "ledgerCash" to minorUnits(ledgerCash), "ledgerEscrow" to minorUnits(ledgerEscrow),
        "cashLastSeq" to cashLastSeq, "escrowLastSeq" to escrowLastSeq,
        "reconciles" to (cash == ledgerCash && escrow == ledgerEscrow),
        "neverNegative" to neverNegative,
    )
}

class LedgerWebApp private constructor(
    val directory: Path,
    private val ledger: Ledger,
    private val ledgerId: String,
    private val wallet: SportsbookWallet,
    private val readModel: ReadModel,
    private val book: Book,
    private val simulator: Simulator,
    private val stats: Stats,
    private val audit: Audit,
    private val allowedHosts: Set<String>,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    private lateinit var server: HttpServer
    val port: Int get() = server.address.port

    companion object {
        const val LEDGER_ID = "sportsbook-web"
        val CUSTOMERS = listOf("alice", "bob", "carol", "dave", "erin", "farid", "grace", "hiro", "ines", "jamal", "kofi", "lena")
        private val signingKeyBootstrapLock = Any()

        fun start(opts: WebOptions): LedgerWebApp {
            val allowedHosts = opts.allowedRequestHosts()
            val dir = opts.dataDir ?: Files.createTempDirectory("pam-ledger-web")
            Files.createDirectories(dir)
            val signer = Ed25519TreeHeadSigner(demoKeyPair(dir))
            val resources = ArrayList<AutoCloseable>()
            try {
                val ledger = Ledger.open(LedgerConfig(directory = dir, ledgerId = LEDGER_ID, signer = signer, checkpointEvery = 1_000)).also(resources::add)
                val wallet = SportsbookWallet(ledger)
                val readModel = ReadModel(ledger).also(resources::add)
                val book = Book(wallet, readModel, dir)
                val simulator = Simulator(book, wallet).also(resources::add)
                val stats = Stats(readModel).also(resources::add)
                val audit = Audit(ledger, dir, LEDGER_ID, listOf(signer.publicKey)).also(resources::add)
                val executor = Executors.newVirtualThreadPerTaskExecutor().also(resources::add)
                val server = HttpServer.create(InetSocketAddress(opts.host, opts.port), 0)
                resources.add(AutoCloseable { server.stop(0) })
                val app = LedgerWebApp(dir, ledger, LEDGER_ID, wallet, readModel, book, simulator, stats, audit, allowedHosts, resources)
                app.server = server
                server.executor = executor
                server.createContext("/") { ex -> app.handle(ex) }

                // Fixed deposit keys make restarting demo data exactly-once.
                CUSTOMERS.forEachIndexed { i, name -> wallet.deposit(name, (100 + 40L * i) * 100, "EUR", "seed-$name") }
                if (book.fixtures().isEmpty()) repeat(6) { book.addFixture() }
                server.start()
                return app
            } catch (failure: Throwable) {
                closeResources(resources, failure)
                throw failure
            }
        }

        /**
         * The checkpoint signing key, kept beside the demo ledger so a restarted app can still
         * verify its own earlier checkpoints. DEMO ONLY: production keys live in an HSM.
         */
        private fun demoKeyPair(dir: Path): KeyPair = synchronized(signingKeyBootstrapLock) {
            val lockFile = dir.resolve("demo-signing-key.lock")
            FileChannel.open(lockFile, CREATE, WRITE).use { lockChannel ->
                lockChannel.lock().use { loadOrCreateDemoKeyPair(dir) }
            }
        }

        private fun loadOrCreateDemoKeyPair(dir: Path): KeyPair {
            val file = dir.resolve("demo-signing-key.properties")
            val kf = KeyFactory.getInstance("Ed25519")
            if (Files.exists(file)) {
                val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
                val d = Base64.getDecoder()
                return KeyPair(kf.generatePublic(X509EncodedKeySpec(d.decode(p.getProperty("public")))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(d.decode(p.getProperty("private")))))
            }
            val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val e = Base64.getEncoder()
            val properties = Properties().apply {
                setProperty("public", e.encodeToString(kp.public.encoded))
                setProperty("private", e.encodeToString(kp.private.encoded))
            }
            val temporary = Files.createTempFile(dir, "demo-signing-key-", ".tmp")
            try {
                FileChannel.open(temporary, WRITE).use { channel ->
                    val output = Channels.newOutputStream(channel)
                    properties.store(output, "DEMO ONLY. Production signing keys must live in an HSM.")
                    output.flush()
                    channel.force(true)
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file)
                }
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    FileChannel.open(dir, READ).use { it.force(true) }
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            return kp
        }
    }

    // =========================================================================
    //  HTTP plumbing
    // =========================================================================

    private class Request(val method: String, val path: List<String>, val params: Map<String, String>) {
        fun str(name: String) = params[name]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing '$name'")
        fun long(name: String) = str(name).toLongOrNull() ?: throw IllegalArgumentException("'$name' must be a whole number")
        fun int(name: String) = str(name).toIntOrNull() ?: throw IllegalArgumentException("'$name' must be a whole number")
    }

    private class HttpError(val status: Int, val code: String, message: String) : RuntimeException(message)

    private fun handle(ex: HttpExchange) {
        try {
            val uri = ex.requestURI
            validateRequestOrigin(ex)
            if (!uri.rawPath.startsWith("/api/")) return serveStatic(ex, uri.path)
            val params = HashMap<String, String>()
            parseForm(uri.rawQuery, params)
            if (ex.requestMethod == "POST") parseForm(ex.requestBody.readAllBytes().toString(Charsets.UTF_8), params)
            val segments = uri.rawPath.removePrefix("/api/").trimEnd('/').split('/').map {
                URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8)
            }
            respond(ex, 200, route(Request(ex.requestMethod, segments, params)))
        } catch (e: Throwable) {
            val (status, code) = when (e) {
                is HttpError -> e.status to e.code
                is InsufficientFundsException -> 409 to "insufficient_funds"
                is IdempotencyConflictException -> 409 to "idempotency_conflict"
                is VersionConflictException -> 409 to "version_conflict"
                is CurrencyMismatchException, is AmountOverflowException -> 422 to "rejected"
                is NotFoundException -> 404 to "not_found"
                is OverloadedException -> 503 to "overloaded"
                is OutcomeUnknownException -> 504 to "outcome_unknown"
                is LedgerFailedException, is LedgerClosedException -> 503 to "ledger_unavailable"
                is TamperDetectedException -> 500 to "tamper_detected"
                is IllegalArgumentException -> 400 to "bad_request"
                else -> 500 to "internal_error"
            }
            if (status == 500) e.printStackTrace()
            runCatching { respond(ex, status, mapOf("error" to code, "message" to (e.message ?: code))) }
        } finally {
            ex.close()
        }
    }

    private fun validateRequestOrigin(ex: HttpExchange) {
        val port = server.address.port
        val hostUri = ex.requestHeaders.getFirst("Host")?.let { runCatching { URI("http://$it") }.getOrNull() }
        val host = hostUri?.host?.removeSurrounding("[", "]")?.lowercase()
        val hostPort = hostUri?.port?.takeIf { it >= 0 } ?: 80
        if (hostUri == null || hostUri.rawUserInfo != null || hostUri.rawPath.isNotEmpty() ||
            hostUri.rawQuery != null || hostUri.rawFragment != null || hostPort != port || host !in allowedHosts) {
            throw HttpError(403, "invalid_host", "request Host is not allowed")
        }

        fun sameOrigin(value: String, isReferer: Boolean): Boolean {
            val origin = runCatching { URI(value) }.getOrNull() ?: return false
            val originPort = origin.port.takeIf { it >= 0 } ?: 80
            return origin.scheme == "http" && origin.host?.removeSurrounding("[", "]")?.equals(host, true) == true &&
                originPort == port && origin.rawUserInfo == null &&
                (isReferer || (origin.rawPath.isEmpty() && origin.rawQuery == null && origin.rawFragment == null))
        }
        val origin = ex.requestHeaders.getFirst("Origin")
        val referer = ex.requestHeaders.getFirst("Referer")
        if ((origin != null && !sameOrigin(origin, false)) ||
            (origin == null && referer != null && !sameOrigin(referer, true)) ||
            (ex.requestMethod == "POST" && origin == null && referer == null) ||
            ex.requestHeaders.getFirst("Sec-Fetch-Site")?.equals("cross-site", true) == true) {
            throw HttpError(403, "cross_origin", "mutations require a same-origin browser request")
        }
    }

    private fun parseForm(raw: String?, into: MutableMap<String, String>) {
        if (raw.isNullOrEmpty()) return
        for (pair in raw.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            into[URLDecoder.decode(pair.substring(0, i), Charsets.UTF_8)] = URLDecoder.decode(pair.substring(i + 1), Charsets.UTF_8)
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.of(body).toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveStatic(ex: HttpExchange, path: String) {
        val name = when (path) { "/", "/index.html" -> "index.html"; "/app.js" -> "app.js"; "/app.css" -> "app.css"; else -> null }
        val bytes = name?.let { javaClass.getResourceAsStream("/web/$it")?.use { s -> s.readAllBytes() } }
        if (bytes == null) {
            ex.sendResponseHeaders(404, -1)
            return
        }
        val type = when { name.endsWith(".js") -> "text/javascript"; name.endsWith(".css") -> "text/css"; else -> "text/html" }
        ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // =========================================================================
    //  Routes
    // =========================================================================

    private fun route(r: Request): Any? {
        val p = r.path
        fun get(vararg pattern: String) = r.method == "GET" && matches(p, pattern)
        fun post(vararg pattern: String) = r.method == "POST" && matches(p, pattern)
        return when {
            get("overview") -> overview()

            // ---- Customer ----
            get("players") -> searchPlayers(r.params["q"] ?: "")
            get("players", "*") -> playerDetail(p[1])
            post("players", "*", "deposit") -> receipt(wallet.deposit(p[1], r.long("amount"), "EUR", r.str("ref")))
            post("players", "*", "withdraw") -> receipt(wallet.withdraw(p[1], r.long("amount"), "EUR", r.str("ref")))
            post("players", "*", "bets") -> receipt(book.placeBet(p[1], r.str("fixture"), r.str("selection"), r.long("stake"), r.str("betId")))
            get("bets", "*", "cashout") -> mapOf("betId" to p[1], "offer" to minorUnits(book.cashOutOffer(p[1])))
            post("bets", "*", "cashout") -> receipt(book.cashOut(p[1]))

            // ---- Operator ----
            get("fixtures") -> fixtures()
            post("fixtures") -> fixtureView(book.addFixture())
            post("fixtures", "*", "settle") -> book.settle(p[1], r.str("result")).let {
                mapOf("fixture" to it.fixtureId, "result" to it.result, "won" to it.won, "lost" to it.lost, "voided" to it.voided, "alreadySettled" to it.alreadySettled)
            }
            get("operator") -> operator()

            // ---- Simulator ----
            get("simulator") -> simulatorView()
            get("simulator", "events") -> {
                val after = r.params["after"]?.toLongOrNull() ?: 0L
                val page = simulator.eventPage(after)
                mapOf("lastId" to page.lastId, "events" to page.events.map {
                    mapOf("id" to it.id, "t" to it.time, "customer" to it.customer, "op" to it.op, "text" to it.text, "ok" to it.ok, "cash" to minorUnits(it.cash))
                })
            }
            post("simulator") -> {
                if (r.params.containsKey("customers")) {
                    simulator.configure(r.int("customers"), r.int("opsPerMinute"), r.params["autoFixtures"] != "false")
                }
                when (r.params["action"]) {
                    "start" -> simulator.start()
                    "pause" -> simulator.pause()
                    "resume" -> simulator.resume()
                    "stop" -> simulator.stop()
                    null, "" -> Unit
                    else -> throw IllegalArgumentException("action must be start, pause, resume or stop")
                }
                simulatorView()
            }

            // ---- Regulator ----
            get("regulator", "checkpoints") -> mapOf(
                "publicKey" to ledger.publicKey.encoded,
                "checkpoints" to audit.checkpoints().map(::sthView).asReversed(),
            )
            post("regulator", "checkpoint") -> sthView(audit.checkpointNow())
            post("regulator", "audit") -> auditView(audit.fullAudit())
            post("regulator", "tamper") -> auditView(audit.tamper(r.str("mode")))
            get("regulator", "players", "*") -> playerAudit(p[2])
            get("proof", "inclusion") -> inclusion(r.long("seq"))
            get("proof", "consistency") -> consistency(r.long("from"), r.long("to"))

            else -> throw HttpError(404, "not_found", "no such endpoint: ${r.method} /api/${p.joinToString("/")}")
        }
    }

    private fun matches(path: List<String>, pattern: Array<out String>) =
        path.size == pattern.size && pattern.indices.all { pattern[it] == "*" || pattern[it] == path[it] }

    // =========================================================================
    //  Views
    // =========================================================================

    private fun receipt(r: Receipt) = mapOf("seq" to r.seq, "chainHash" to r.chainHash, "leafHash" to r.leafHash, "replayed" to r.replayed)

    private fun sumMinorUnits(amounts: Iterable<Long>) = amounts.fold(BigInteger.ZERO) { total, amount ->
        total.add(BigInteger.valueOf(amount))
    }

    private fun entryView(s: SealedEntry) = mapOf(
        "seq" to s.entry.seq,
        "kind" to s.entry.kind,
        "key" to s.entry.idempotencyKey,
        "time" to time(s.entry.timestampMicros),
        "postings" to s.entry.postings.map { mapOf("account" to it.account.value, "amount" to minorUnits(it.amount)) },
        "metadata" to s.entry.metadata,
        "chainHash" to s.chainHash,
    )

    private fun overview(): Map<String, Any?> {
        val head = ledger.head()
        return mapOf(
            "ledgerId" to ledgerId,
            "entries" to head.size,
            "chainHead" to head.chainHead,
            "merkleRoot" to head.merkleRoot,
            "latestCheckpoint" to ledger.latestCheckpoint()?.head?.treeSize,
            "simulator" to simulator.state.name,
        )
    }

    private fun playerSummary(id: String) = mapOf(
        "id" to id,
        "cash" to minorUnits(wallet.cashBalance(id, "EUR")),
        "openBets" to minorUnits(wallet.openBetsBalance(id, "EUR")),
        "automated" to Simulator.isAutomated(id),
    )

    private fun searchPlayers(q: String): Any {
        val all = (CUSTOMERS + readModel.players()).distinct()
        val matching = all.filter { q.isBlank() || it.contains(q.trim(), ignoreCase = true) }
        val ordered = matching.filter { !Simulator.isAutomated(it) } + matching.filter { Simulator.isAutomated(it) }.sorted()
        return mapOf("total" to matching.size, "players" to ordered.take(60).map(::playerSummary))
    }

    private fun betView(b: ReadModel.BetView) = mapOf(
        "betId" to b.betId, "fixture" to b.fixtureId, "selection" to b.selection, "odds" to b.oddsHundredths,
        "stake" to minorUnits(b.stake), "potentialPayout" to minorUnits(b.potentialPayout), "placedSeq" to b.placedSeq,
        "time" to time(b.placedAtMicros), "outcome" to b.outcome, "payout" to b.payout?.let(::minorUnits), "open" to b.isOpen,
    )

    private fun playerDetail(id: String): Any {
        readModel.catchUp()
        val seqs = readModel.seqsForPlayer(id)
        return playerSummary(id) + mapOf(
            "bets" to readModel.betsForPlayer(id, 40).asReversed().map(::betView),
            "history" to seqs.takeLast(40).asReversed().map { entryView(ledger.entry(it)) },
            "entries" to seqs.size,
        )
    }

    private fun fixtureView(f: Book.Fixture): Map<String, Any?> {
        val open = readModel.openBetsOn(f.id)
        val liability = f.odds.keys.associateWith { sel ->
            minorUnits(sumMinorUnits(open.filter { it.selection == sel }.map { it.potentialPayout }))
        }
        return mapOf(
            "id" to f.id, "home" to f.home, "away" to f.away, "odds" to f.odds, "result" to f.result,
            "settlementComplete" to f.settlementComplete,
            "openBets" to open.size, "staked" to minorUnits(sumMinorUnits(open.map { it.stake })), "liability" to liability,
        )
    }

    private fun fixtures(): List<Map<String, Any?>> {
        val ordered = book.fixtures().asReversed()
        val recentCompleted = ordered.asSequence().filter { it.settlementComplete }.take(30).mapTo(HashSet<String>()) { it.id }
        return ordered.filter { !it.settlementComplete || it.id in recentCompleted }.map(::fixtureView)
    }

    private fun operator(): Map<String, Any?> {
        val totals = readModel.totals()
        val open = readModel.openBets()
        return mapOf(
            "players" to readModel.players().size,
            "entries" to totals.entries,
            "deposits" to minorUnits(totals.deposits),
            "withdrawals" to minorUnits(totals.withdrawals),
            "staked" to minorUnits(totals.staked),
            "returned" to minorUnits(totals.returned),
            "ggr" to minorUnits(readModel.balance(Accounts.revenue("EUR"))),
            "openBets" to open.size,
            "openStakes" to minorUnits(sumMinorUnits(open.map { it.stake })),
            "exposure" to minorUnits(open.fold(BigInteger.ZERO) { total, bet ->
                total.add(BigInteger.valueOf(bet.potentialPayout).subtract(BigInteger.valueOf(bet.stake)))
            }),
            "kinds" to totals.kindCounts,
            "series" to stats.series().map {
                mapOf("t" to it.t, "tps" to it.txPerSec, "ggr" to minorUnits(it.ggr), "exposure" to minorUnits(it.exposure), "openBets" to it.openBets)
            },
            "feed" to readModel.recentSeqs().take(25).map { entryView(ledger.entry(it)) },
        )
    }

    private fun simulatorView() = mapOf(
        "state" to simulator.state.name,
        "customers" to simulator.customers,
        "opsPerMinute" to simulator.opsPerMinute,
        "autoFixtures" to simulator.autoFixtures,
        "targetRate" to simulator.targetRate,
        "startedAt" to simulator.startedAtMillis,
        "succeeded" to simulator.succeeded,
        "bets" to simulator.bets.get(),
        "deposits" to simulator.deposits.get(),
        "withdrawals" to simulator.withdrawals.get(),
        "cashOuts" to simulator.cashOuts.get(),
        "rejectedForFunds" to simulator.rejectedForFunds.get(),
        "rejectedOther" to simulator.rejectedOther.get(),
        "shed" to simulator.shed.get(),
        "fixtureRounds" to simulator.fixtureRounds.get(),
        "maxCustomers" to Simulator.MAX_CUSTOMERS,
    )

    private fun sthView(s: SignedTreeHead) = mapOf(
        "treeSize" to s.head.treeSize,
        "rootHash" to s.head.rootHash,
        "chainHead" to s.head.chainHead,
        "time" to time(s.head.timestampMicros),
        "keyId" to s.keyId,
        "ledgerId" to s.head.ledgerId,
        "signature" to s.signature(),
        "signingBytes" to s.head.signingBytes(),
        "verified" to s.verify(ledger.publicKey),
    )

    private fun auditView(a: Audit.AuditResult): Map<String, Any?> {
        val rep: LedgerVerifier.Report = a.report
        return mapOf(
            "ok" to rep.ok, "note" to a.note, "millis" to a.millis, "entries" to rep.entries,
            "checkpointsVerified" to rep.checkpointsVerified, "totals" to rep.totalsByCurrency.mapValues { it.value.toString() },
            "problems" to rep.problems, "merkleRoot" to rep.head?.merkleRoot, "chainHead" to rep.head?.chainHead,
        )
    }

    /** Every entry for one player, with balances recomputed entry by entry from the postings themselves. */
    private fun playerAudit(id: String): Any {
        val committedSize = ledger.head().size
        val cashState = ledger.account(Accounts.cash(id, "EUR"))
        val escrowState = ledger.account(Accounts.openBets(id, "EUR"))
        return replayPlayerAudit(ledger, id, cashState, escrowState, committedSize)
    }

    /** Everything a browser needs to verify, by itself, that entry [seq] is in a signed checkpoint. */
    private fun inclusion(seq: Long): Any {
        require(seq in 0 until ledger.head().size) { "entry #$seq does not exist" }
        val sth = audit.checkpointCovering(seq)
        val proof = ledger.inclusionProof(seq, sth.head.treeSize)
        check(proof.verify()) // the server checks too, but the browser does not rely on it
        val sealed = ledger.entry(seq)
        return mapOf(
            "entry" to entryView(sealed),
            "entryBytes" to sealed.encodedBytes(),
            "leafHash" to proof.leafHash,
            "path" to proof.auditPath,
            "treeSize" to proof.treeSize,
            "sth" to sthView(sth),
            "ledgerId" to ledgerId,
            "publicKey" to ledger.publicKey.encoded,
        )
    }

    /** Proof that checkpoint [from] is a prefix of checkpoint [to]: nothing was rewritten between them. */
    private fun consistency(from: Long, to: Long): Any {
        require(from in 1..to) { "need 1 <= from <= to" }
        val a = audit.checkpointOfSize(from) ?: throw NotFoundException("no checkpoint covers exactly $from entries")
        val b = audit.checkpointOfSize(to) ?: throw NotFoundException("no checkpoint covers exactly $to entries")
        val proof = ledger.consistencyProof(from, to)
        check(MerkleVerify.consistency(from, to, a.head.rootHash, b.head.rootHash, proof))
        return mapOf(
            "proof" to proof, "from" to sthView(a), "to" to sthView(b),
            "ledgerId" to ledgerId, "publicKey" to ledger.publicKey.encoded,
        )
    }

    override fun close() {
        closeResources(resources)
    }
}

private fun closeResources(resources: List<AutoCloseable>, failure: Throwable? = null) {
    var error = failure
    for (resource in resources.asReversed()) {
        try {
            resource.close()
        } catch (closing: Throwable) {
            if (error == null) error = closing else if (closing !== error) error.addSuppressed(closing)
        }
    }
    error?.let { throw it }
}
