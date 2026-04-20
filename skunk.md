# Skunk
Skunk v1.0.0 replaced its tracing dependency from Natchez to otel4s (OpenTelemetry for Scala).

## Session Creation — Use the Fluent Session.Builder API
The old `Session.pooled(...)` / `Session.single(...)` methods are deprecated in v1.0.0. The new idiomatic way is the builder pattern:

Call `.pooled(max)` once at application startup and pass the inner `Resource[IO, Session[IO]]` to the rest of your app. Do not call `.flatten` on the nested Resource — that creates a new pool per session.

## SSL/TLS — Always Enable in Production
SSL is off by default. For production, always configure it:

| Mode | Use Case |
|------|----------|
| `SSL.System` | CA-signed certificates (most production setups) |
| `SSL.Trusted` | Self-signed certificates (dev/staging) |
| `SSL.fromSSLContext(ctx)` | Custom `SSLContext` for advanced control |
| `SSL.fromKeyStoreFile(...)` | Mutual TLS with client certificates |

You can also customize TLS parameters and allow fallback:
```scala
.withSSL(
  SSL.System
    .withTLSParameters(TLSParameters.Default)
    .withFallback(false) // never fall back to unencrypted
)
```

##  Authentication
Skunk supports `trust`, `password`, `md5`, and `scram-sha-256`. For production, use `scram-sha-256` (the strongest option).

For dynamic credentials (e.g., rotating passwords from a secrets manager), v1.0.0 supports credentials evaluated per-session:
```scala
Session.Builder[IO]
  .withCredentials(
    IO(fetchCredentialsFromVault()).map(c => Session.Credentials(c.user, Some(c.password)))
  )
  .pooled(10)
  ```
## Unix Domain Sockets (New in v1.0.0)
For same-host connections (e.g., sidecar or local Postgres), use Unix sockets for better performance and peer authentication:
```scala
Session.Builder[IO]
  .withUnixSockets                        // defaults to /tmp/.s.PGSQL.5432
  .withUser("postgres")                   // peer auth: no password needed
  .withDatabase("mydb")
  .pooled(10)
 ```
 ##  Observability — otel4s (Replaces Natchez)
Skunk v1.0.0 uses otel4s for OpenTelemetry-native tracing and metrics. Both `Tracer[F]` and `Meter[F]` are required implicits.  
```scala
def getTelemetry[F[_]: Async: LiftIO]: Resource[F, (Tracer[F], Meter[F])] =
  OtelJava.autoConfigured[F]()
    .evalMap { otel =>
      (otel.tracerProvider.tracer("my-app").get,
       otel.meterProvider.meter("my-app").get).tupled
    }

def run(args: List[String]): IO[ExitCode] =
  getTelemetry[IO].use { case (tracer, meter) =>
    implicit val T = tracer
    implicit val M = meter
    // now Session.Builder[IO] picks up real tracing + metrics
    ???
  }
  ``` 
  Skunk automatically emits:
- `Traces` with spans for `pool.alloc`, `pool.free`, `recycle`, `startup`, `query`, `execute`, etc. using `OpenTelemetry semantic conventions` (`db.system.name`, `db.response.status_code`, `db.collection.name`, etc.)
- `Metrics` via the `db.client.operation.duration` histogram with configurable bucket boundaries

### For Dev/Testing (No-Op)
```scala
implicit val tracer: Tracer[IO] = Tracer.noop
implicit val meter: Meter[IO] = Meter.noop
```

## Data Redaction — Protect Sensitive Values

Skunk v1.0.0 provides three `RedactionStrategy` options to control what appears in exceptions and traces:

| Strategy | Behavior |
|----------|----------|
| `RedactionStrategy.OptIn` (default) | Only values from `.redacted` encoders are redacted |
| `RedactionStrategy.All` | All values are redacted — safest for production |
| `RedactionStrategy.None` | Nothing is redacted — useful for debugging |

`Per-encoder redaction`:
```scala
// Mark a specific field as sensitive
val insertUser = sql"INSERT INTO users VALUES ($varchar, ${varchar.redacted})"
  .command // the password parameter will be shown as "?" in logs
```

`Global redaction (recommended for production)`:
```scala
Session.Builder[IO]
  .withRedactionStrategy(RedactionStrategy.All)
    .pooled(10)
```
## Connection Pool Recycling
The pool uses `Recyclers` to validate sessions before reuse. By default, `pooled()` uses `Recyclers.full`, which:
- `Closes evicted prepared statements` on the server
- `Checks the session is idle` (no ongoing transaction)
- `Runs UNLISTEN *` to remove all channel listeners
- `Runs RESET ALL` to reset session variables

If your app doesn't use `LISTEN/NOTIFY` or set session variables, use the lighter `Recyclers.minimal` which skips steps 3–4 and just runs a trivial `VALUES (true)` query as a health check.

## Typing Strategy — Custom Types & Enums
If your database uses user-defined types (enums, composite types, domains), you must switch from the default `BuiltinsOnly` to `SearchPath`:
```scala
Session.Builder[IO]
  .withUserAndPassword("app", "pass")
  .withDatabase("mydb")
  .withTypingStrategy(TypingStrategy.SearchPath)  // required for enums!
  .pooled(10)
```

| Strategy | Behavior | Startup Cost |
|----------|----------|--------------|
| `TypingStrategy.BuiltinsOnly` (default) | Only knows built-in Postgres types (`int4`, `varchar`, etc.). No DB round-trip. | Zero |
| `TypingStrategy.SearchPath` | Queries `pg_type`/`pg_attribute` at session startup to discover user-defined types on the search path. | One round-trip |

With `SearchPath`, types are read once at session init. If you create a new type after `session init`, it won't be visible until a new session is created.

## Transactions — Isolation Level & Access Mode
Skunk v1.0.0 supports full control over transaction characteristics:
```scala
import skunk.data.{TransactionIsolationLevel, TransactionAccessMode}

// Read-only queries with serializable isolation (strongest guarantee)
s.transaction(
  TransactionIsolationLevel.Serializable,
  TransactionAccessMode.ReadOnly
).use { xa =>
  s.execute(myReadQuery)
}

// Default: ReadCommitted + ReadWrite
s.transaction.use { xa =>
  for {
    sp <- xa.savepoint               // create savepoint
    _  <- s.execute(insertCmd)(args)
           .recoverWith {
             case SqlState.UniqueViolation(_) =>
               xa.rollback(sp)       // rollback to savepoint, txn continues
           }
  } yield ()
}
```

| Isolation Level | Use Case |
|-----------------|----------|
| `ReadCommitted` (default) | General OLTP workloads |
| `RepeatableRead` | Read-heavy workloads needing snapshot consistency |
| `Serializable` | Financial/critical data requiring full serializability |
| `ReadUncommitted` | Postgres treats this as `ReadCommitted` |

## Twiddle Lists — Use *: (Not ~)
v1.0.0 standardizes on the `*:` twiddle-list syntax (from the Twiddles library) and deprecates the old `~` pair-based syntax:
```scala
// ✅ v1.0.0 way — twiddle list via *:
case class Country(code: String, name: String, pop: Int)

val query: Query[String, Country] =
  sql"""
    SELECT code, name, population
    FROM country
    WHERE name LIKE $varchar
  """.query(bpchar(3) *: varchar *: int4)   // *: composes codecs
     .to[Country]                            // .to maps to case class

// ❌ Deprecated — old ~ pair syntax
// .query(bpchar(3) ~ varchar ~ int4).gimap[Country]
```

## executeDiscard — Multi-Statement Execution
v1.0.0 adds executeDiscard for running arbitrary multi-statement SQL (migrations, DDL scripts, etc.) where you don't care about returned rows/completions:
```scala
// Run multiple DDL statements in one call
s.executeDiscard(
  sql"""
    ALTER TABLE city ADD COLUMN idd SERIAL;
    SELECT setval('city_idd_seq', max(id)) FROM city;
    ALTER TABLE city DROP COLUMN idd
  """.command
)
```
 Regular `execute/prepare` still reject multi-statement strings. Only `executeDiscard` supports them.

 ## Pool Internals — Understanding Leak Detection
The pool in v1.0.0 has `built-in resource leak detection`. At pool shutdown it verifies all slots have been returned:
- If slots are missing → raises `Pool.ResourceLeak` with diagnostics
- If deferrals (pending waiters) remain → completes them with `ShutdownException`
Production implications:

- Always `join` or `cancel` fibers that hold pool sessions before the pool shuts down
- Avoid `Resource.allocated` on pooled sessions — use `Resource.use` instead
- The pool emits OpenTelemetry spans: `pool.allocate`, `pool.free`, `recycle`, `dispose`

##  Read Timeout & Statement Timeout
Two complementary timeout mechanisms:
```scala
import scala.concurrent.duration._

Session.Builder[IO]
  .withReadTimeout(30.seconds)   // TCP-level: how long to wait for a response
  .withConnectionParameters(
    Session.DefaultConnectionParameters ++ Map(
      "statement_timeout" -> "15000"  // Postgres-level: kill query after 15s (in ms)
    )
  )
  .pooled(10)
```

| Mechanism | Level | Behavior on Timeout |
|-----------|-------|---------------------|
| `withReadTimeout` | TCP socket | Raises an exception in your effect; connection likely unusable |
| `statement_timeout` | Postgres server | Server cancels the query; session remains healthy |

For production, `statement_timeout` is `preferred` — it cancels the query server-side and the session remains valid for reuse.

## Statement Caches — Tuning for Workload
The pool maintains three levels of caching to avoid repeated round-trips:
```scala
Session.Builder[IO]
  .withCommandCacheSize(4096)  // Describe cache for commands (default: 2048)
  .withQueryCacheSize(4096)    // Describe cache for queries  (default: 2048)
  .withParseCacheSize(4096)    // Parse cache (LRU, pool-wide) (default: 2048)
  .pooled(10)
```

| Cache | Scope | Purpose |
|-------|-------|---------|
| Command cache | Pool-wide | Avoids re-describing (schema-checking) commands |
| Query cache | Pool-wide | Avoids re-describing queries |
| Parse cache | Pool-wide (LRU) | Avoids re-parsing statements; evicted entries are closed on the server via `closeEvictedPreparedStatement` |

If your schema changes at runtime (migrations), set cache sizes to `0` to disable caching

## Socket Options & Connection Parameters
Fine-grained TCP and Postgres tuning:
```scala
import fs2.io.net.SocketOption

Session.Builder[IO]
  .withSocketOptions(List(
    SocketOption.noDelay(true),       // default: enabled (TCP_NODELAY)
    SocketOption.keepAlive(true),     // keep-alive for long-lived pool connections
  ))
  .withConnectionParameters(
    Session.DefaultConnectionParameters ++ Map(
      "statement_timeout"   -> "30000",  // 30s query timeout
      "lock_timeout"        -> "10000",  // 10s lock wait timeout
      "idle_in_transaction_session_timeout" -> "60000", // kill idle-in-txn sessions
    )
  )
  .pooled(10)
```
The defaults set by Skunk:

| Parameter | Default Value |
|-----------|--------------|
| `client_min_messages` | `WARNING` |
| `DateStyle` | `ISO, MDY` |
| `IntervalStyle` | `iso_8601` |
| `client_encoding` | `UTF8` |

## prepareR — Non-Cached Prepared Statements
v1.0.0 distinguishes between `cached` and `uncached` prepared statements:
```scala
// Cached (default) — lives as long as the session, stays in parse cache
val cached: F[PreparedQuery[F, String, Country]] = s.prepare(selectByName)

// Uncached (Resource) — closed when the Resource is released
val uncached: Resource[F, PreparedQuery[F, String, Country]] = s.prepareR(selectByName)
```
Use `prepareR` when:
- You have one-off dynamic queries that shouldn't pollute the cache
- You want explicit lifecycle control over server-side prepared statements

## Full Production Session.Builder Reference
```scala
import cats.effect._
import skunk._
import scala.concurrent.duration._
import fs2.io.net.SocketOption
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.metrics.Meter

def productionPool[F[_]: Temporal: Tracer: Meter: fs2.io.net.Network: cats.effect.std.Console]
  : Resource[F, Resource[F, Session[F]]] =
  Session.Builder[F]
    // Connection
    .withHost("db.prod.internal")
    .withPort(5432)
    .withDatabase("myapp")
    // Auth (dynamic credentials from vault)
    .withCredentials(fetchCredsFromVault[F])
    // Security
    .withSSL(SSL.System)
    .withRedactionStrategy(RedactionStrategy.All)
    // Types
    .withTypingStrategy(TypingStrategy.SearchPath)
    // Network
    .withSocketOptions(List(
      SocketOption.noDelay(true),
      SocketOption.keepAlive(true),
    ))
    .withReadTimeout(30.seconds)
    // Postgres params
    .withConnectionParameters(
      Session.DefaultConnectionParameters ++ Map(
        "statement_timeout" -> "30000",
        "idle_in_transaction_session_timeout" -> "60000",
      )
    )
    // Caching
    .withCommandCacheSize(2048)
    .withQueryCacheSize(2048)
    .withParseCacheSize(2048)
    // Pool
    .pooled(20)
```    

### What Skunk's Pool Does
Looking at the Pool.scala implementation, Skunk's pool is an `in-process, single-JVM connection pool`:
- Manages up to `max` real Postgres TCP connections within one application instance
- Uses a slot-based deque with `Deferred`-based back-pressure — when all slots are taken, callers semantically block until a connection is returned
- Runs `Recyclers.full` on return (idle check, `UNLISTEN *`, `RESET ALL`, close evicted statements)
    Detects resource leaks at shutdown
    Fully instrumented with OpenTelemetry spans (`pool.allocate`, `pool.free`, `recycle`, `dispose`)

This is functionally equivalent to HikariCP or c3p0 — `it's application-level pooling`.

### Where PgBouncer Adds Value
PgBouncer operates at a completely different layer — it's an external, network-level connection multiplexer that sits between your app(s) and Postgres:

| Concern | Skunk Pool | PgBouncer |
|---------|-----------|-----------|
| Scope | Single JVM process | All processes/services across the network |
| Connection reuse | Within one app instance | Across all app instances |
| Multiplexing | No — each pooled session = 1 real Postgres backend | Yes — N app connections → M Postgres backends (N >> M) |
| Scaling protection | Caps connections per instance | Caps total connections to Postgres |
| Pooling mode | Session-level only | Session, transaction, or statement-level |

### When You Still Need PgBouncer
1. Multiple App Instances (Horizontal Scaling)

This is the #1 reason. If you run `K` replicas of your app, each with `pooled(20)`, Postgres sees `K × 20` connections:
```sh
3 replicas × 20 pool size = 60 real connections
10 replicas × 20 pool size = 200 real connections  ← approaching default max_connections (100)
50 replicas × 20 pool size = 1000 real connections ← Postgres will refuse connections
```
PgBouncer sits in front and multiplexes all 1000 app-side connections into, say, 50 real Postgres backends
2. Serverless / Short-Lived Processes
Lambda functions, Kubernetes Jobs, CLI tools — each creates connections briefly. Without PgBouncer, each cold start opens a new TCP connection + does the Postgres handshake + TLS negotiation. PgBouncer keeps warm connections ready.

3. Transaction-Level Pooling
Skunk's pool is session-level only — a connection is held for the entire `p.use { session => ... }` block, even during application computation between queries. PgBouncer in `transaction` mode can release the backend connection between transactions, achieving much higher multiplexing ratios.

## Dynamic Search with AppliedFragment
A production app needs filterable, paginated queries. Skunk's AppliedFragment is how you build dynamic SQL safely

## Real-Time Notifications via LISTEN/NOTIFY
For a fintech app — push balance changes, fraud alerts, or settlement notifications to connected clients in real-time

### Pool Sizing 
`Optimal connections = (CPU cores × 2) + effective_spindle_count`
Each Postgres connection costs ~10MB of RAM. 
For a 4-core Postgres server with SSD: (4 × 2) + 1 = 9
```scala
// ❌ WRONG — 50 connections on a 4-core DB = connection thrashing
Session.Builder[F].pooled(50)

// ✅ RIGHT — small pool, let Skunk's Deferred-based queue handle back-pressure
Session.Builder[F].pooled(10)

// At 1000 RPS with 5ms avg query time:
// Throughput = pool_size / avg_query_time = 10 / 0.005 = 2000 RPS ✅
// Skunk's pool queues callers via Deferred — they semantically block, not thread-block
```

### Read Replicas — Separate Pools
Split reads and writes across different Postgres instances
```scala
case class DbPools[F[_]](
  writer: Resource[F, Session[F]],  // primary
  reader: Resource[F, Session[F]],  // replica(s)
)

object DbPools {
  def make[F[_]: Temporal: Tracer: Meter: Console: Network](cfg: AppConfig): Resource[F, DbPools[F]] =
    for {
      writer <- Session.Builder[F]
                  .withHost(cfg.db.primaryHost)
                  .withUserAndPassword(cfg.db.user, cfg.db.password)
                  .withDatabase(cfg.db.database)
                  .withTypingStrategy(TypingStrategy.SearchPath)
                  .withReadTimeout(10.seconds)
                  .withConnectionParameters(
                    Session.DefaultConnectionParameters ++ Map(
                      "statement_timeout" -> "5000",   // 5s for writes
                    )
                  )
                  .pooled(cfg.db.writerPoolSize)       // small: 5-10

      reader <- Session.Builder[F]
                  .withHost(cfg.db.replicaHost)
                  .withUserAndPassword(cfg.db.user, cfg.db.password)
                  .withDatabase(cfg.db.database)
                  .withTypingStrategy(TypingStrategy.SearchPath)
                  .withReadTimeout(15.seconds)
                  .withConnectionParameters(
                    Session.DefaultConnectionParameters ++ Map(
                      "statement_timeout"        -> "10000",   // 10s for reads
                      "default_transaction_read_only" -> "on", // safety: reject writes
                    )
                  )
                  .pooled(cfg.db.readerPoolSize)       // larger: 15-20
    } yield DbPools(writer, reader)
}
```

### Write Batching / Micro-Batching
Collect writes from many concurrent requests and flush as a single bulk INSERT:
```scala
import cats.effect.std.Queue
import fs2.Stream

trait WriteBatcher[F[_], A] {
  def enqueue(item: A): F[Unit]
}

object WriteBatcher {
  def make[F[_]: Temporal](
    pool:      Resource[F, skunk.Session[F]],
    maxBatch:  Int           = 500,
    maxDelay:  FiniteDuration = 50.millis,
  )(flush: (skunk.Session[F], List[A]) => F[Unit]): Resource[F, WriteBatcher[F, A]] =
    for {
      queue <- Resource.eval(Queue.unbounded[F, A])
      _     <- Stream
                 .fromQueueUnterminated(queue)
                 .groupWithin(maxBatch, maxDelay)  // collect up to 500 items or 50ms
                 .evalMap { chunk =>
                   pool.use(s => flush(s, chunk.toList))
                 }
                 .compile.drain.background
    } yield new WriteBatcher[F, A] {
      def enqueue(item: A): F[Unit] = queue.offer(item)
    }
}
```

### Rate Limiting with Token Bucket
```scala
import cats.effect.std.Semaphore

trait RateLimiter[F[_]] {
  def throttle[A](fa: F[A]): F[A]
}

object RateLimiter {
  // Simple semaphore-based concurrency limiter
  def make[F[_]: Concurrent](maxConcurrent: Int): F[RateLimiter[F]] =
    Semaphore[F](maxConcurrent.toLong).map { sem =>
      new RateLimiter[F] {
        def throttle[A](fa: F[A]): F[A] = sem.permit.use(_ => fa)
      }
    }
}
```

## why instrument repo and services?
Repo-level instrumentation tells you about database performance — how long each SQL query takes, which queries are slow, which ones error. When a latency spike hits, you can immediately see whether `WalletRepo.debit` or `LedgerRepo.insertEntry` is the bottleneck. The span also captures the exact query that failed, with attributes like `wallet.id` so you can correlate with specific rows.

Service-level instrumentation tells you about business operation performance — how long an end-to-end transfer takes (which includes multiple repo calls, locking, ledger entries). A `TransferService.transfer` span is the parent; the repo spans are children nested inside it. This gives you a trace waterfall showing exactly where time is spent within a single business operation. The `service.transfer.completed` counter broken down by `txn.type` is pure business metric — not something a repo would know about.


For simple pass-through methods like `WalletService.getWallet` → `WalletRepo.findById`, the repo span is indeed redundant — the service span already tells you everything.
The value shows up in composite operations. `TransferService.transfer` calls ~9 repo methods in sequence:
```sh
TransferService.transfer (200ms)          ← service span
  ├─ TransactionRepo.findByReference (3ms)  ← which of these is slow?
  ├─ WalletRepo.findByIdForUpdate (5ms)
  ├─ WalletRepo.findByIdForUpdate (4ms)
  ├─ TransactionRepo.create (8ms)
  ├─ WalletRepo.debit (120ms)              ← this one
  ├─ WalletRepo.credit (6ms)
  ├─ LedgerRepo.insertEntry (5ms)
  ├─ LedgerRepo.insertEntry (4ms)
  ├─ TransactionRepo.complete (3ms)
  └─ AuditRepo.log (2ms)
```  
Without repo spans, you'd just see "transfer took 200ms" with no breakdown. You'd know the service is slow but not that `WalletRepo.debit` is the bottleneck

The fix we used: reorder the case class so the opaque-typed field isn't last — putting a standard type (like `timestamptz.opt` → `Option[OffsetDateTime]`) at the end instead:
```scala
// Fails — RoleId is last, Prepend can't reduce
(int8 *: int8.opt *: timestamptz *: timestamptz.opt *: roleId).to[UserRole]

// Works — RoleId is second, Option[OffsetDateTime] is last (known type)
(int8 *: roleId *: int8.opt *: timestamptz *: timestamptz.opt).to[UserRole]
```
```scala
type Prepend[A, B] = B match
  case EmptyTuple => A *: EmptyTuple
  case h *: t     => A *: h *: t
```  
`int8 *: varchar *: roleId`
Evaluation is right-to-left. The compiler must decide at each step: "is the right-hand type a Tuple or not?"

Failing example: `varchar *: roleId` → compiler must reduce Prepend[String, RoleId]
Substitute `B = RoleId`:
```sh
RoleId match
  case EmptyTuple => ...    // Is RoleId <: EmptyTuple?   → NO (EmptyTuple is final)
  case h *: t     => ...    // Is RoleId <: *:[h, t]?     → STUCK
```  
The core asymmetry: `Prepend[A, B]` only pattern-matches on `B`. `A` is never scrutinized — it's just placed into the result. So an opaque type in the `A` position (any non-last position in the chain) is fine. Only the last codec in the chain lands in the `B` position, where the match type must decide "is this a Tuple or not?"

 `*:` ends with `:`, which makes it right-associative in Scala. Any operator ending in `:` associates to the right. So: `int8 *: varchar *: roleId` parses as `int8 *: (varchar *: roleId)`, not `(int8 *: varchar) *: roleId`. The rightmost codec is the one that must be a known type (not opaque) for the match type to reduce properly.

 ```sh
 .single  → Resource[F, Session[F]]
              │         │
              │         └── you get ONE session directly
              └── opening this acquires the connection

.pooled  → Resource[F, Resource[F, Session[F]]]
              │         │         │
              │         │         └── a session borrowed from the pool
              │         └── borrowing (inner Resource)
              └── pool lifecycle (outer Resource)
```

```sh
HTTP Request arrives
     │
     ▼
pool.use { session =>        ← borrow a Session (= connection) from the pool
  for {                         if all 10 are busy, this semantically blocks (Deferred)
    wallet <- walletRepo.findById(id)   ← uses session's connection
    _      <- walletRepo.updateBalance  ← same connection, serialized via Exchange
  } yield wallet
}                            ← session returned to pool, recycled
     │
     ▼
HTTP Response sent
```

A session always wrap a TCP connection to Postgres. When you call `pool.use { session => ... }`, you are borrowing a session (and thus a connection) from the pool. If all sessions are currently in use, your call will semantically block until one is returned. Once you have the session, all queries/commands executed within that block use the same underlying connection. When the block finishes, the session is returned to the pool and recycled (idle check, `UNLISTEN *`, `RESET ALL`, etc.) before being made available for the next borrower.

`.single` is `pooled(1).flatten` — a pool of size 1