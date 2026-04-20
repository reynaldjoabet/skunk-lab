package kudi

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import cats.FlatMap
import fs2.Stream

import org.typelevel.otel4s.metrics.*
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/**
  * Polls PostgreSQL system views + application tables every N seconds and records metrics via
  * otel4s.
  *
  * System metrics (pg_stat_database, pg_stat_activity, pg_stat_user_tables, pg_locks):
  *   - db.pg.connections.{active,idle,idle_in_transaction,waiting,total}
  *   - db.pg.cache_hit_ratio
  *   - db.pg.xact.{commit,rollback}
  *   - db.pg.deadlocks
  *   - db.pg.temp_bytes
  *   - db.pg.rows.{returned,fetched,inserted,updated,deleted}
  *   - db.pg.database_size
  *   - db.pg.oldest_txn_age
  *   - db.pg.dead_tuples
  *   - db.pg.live_tuples
  *   - db.pg.table_bloat_ratio
  *   - db.pg.seq_scans
  *   - db.pg.idx_scans
  *   - db.pg.locks.{blocked,total}
  *   - db.pg.replication_lag
  *
  * Business metrics (kudi tables):
  *   - db.kudi.users.total
  *   - db.kudi.users.active_today
  *   - db.kudi.wallets.total
  *   - db.kudi.txns.pending
  *   - db.kudi.txns.failed_today
  *   - db.kudi.txns.completed_today
  *   - db.kudi.volume.today
  *   - db.kudi.volume.avg_txn_size
  *   - db.kudi.ledger.balanced (1 = SUM(entries) is 0, 0 = imbalance detected)
  */
object DbMetricsCollector {

  // ── Gauge emulation ────────────────────────────────────────────────────
  // UpDownCounter + Ref tracks absolute values by recording deltas.

  private class GaugeRef[F[_]: FlatMap](
    counter: UpDownCounter[F, Long],
    prev: Ref[F, Long]
  ) {

    def set(value: Long): F[Unit] =
      prev.getAndSet(value).flatMap(p => counter.add(value - p))

  }

  private class GaugeDoubleRef[F[_]: FlatMap](
    hist: Histogram[F, Double],
    prev: Ref[F, Double]
  ) {

    def record(value: Double): F[Unit] =
      prev.set(value) *> hist.record(value)

  }

  // Cumulative PG counters: emit only the delta since last poll.
  private class DeltaRef[F[_]: FlatMap](
    counter: Counter[F, Long],
    prev: Ref[F, Long]
  ) {

    def observe(cumulative: Long): F[Unit] =
      prev.getAndSet(cumulative).flatMap(p => counter.add(math.max(cumulative - p, 0L)))

  }

  // ── Row types ──────────────────────────────────────────────────────────

  private case class SystemStats(
    connActive: Long,
    connIdle: Long,
    connIdleInTxn: Long,
    connWaiting: Long,
    connTotal: Long,
    xactCommit: Long,
    xactRollback: Long,
    blksRead: Long,
    blksHit: Long,
    deadlocks: Long,
    tempBytes: Long,
    tupReturned: Long,
    tupFetched: Long,
    tupInserted: Long,
    tupUpdated: Long,
    tupDeleted: Long,
    dbSize: Long,
    oldestTxnAge: Long,
    deadTuples: Long,
    liveTuples: Long,
    seqScans: Long,
    idxScans: Long,
    locksBlocked: Long,
    locksTotal: Long,
    replicationLag: Long
  )

  private case class BusinessStats(
    users: Long,
    activeUsersToday: Long,
    wallets: Long,
    pendingTxns: Long,
    failedTxnsToday: Long,
    completedTxnsToday: Long,
    volumeToday: Long,
    avgTxnSize: Long,
    ledgerBalanced: Long
  )

  private case class AuthStats(
    activeSessions: Long,
    expiredNotCleaned: Long,
    uniqueActiveUsers: Long,
    loginAttemptsLastHour: Long,
    failedLoginsLastHour: Long,
    failureRatePct: Long,
    distinctFailedIps: Long,
    activeApiKeys: Long,
    pendingKycDocs: Long,
    mfaEnabledUsers: Long
  )

  // ── Queries ────────────────────────────────────────────────────────────
  // Three round-trips per poll cycle: system, locks+replication, business.

  private val systemQ: Query[Void, SystemStats] =
    sql"""
      WITH conn AS (
        SELECT
          count(*) FILTER (WHERE state = 'active')                 AS active,
          count(*) FILTER (WHERE state = 'idle')                   AS idle,
          count(*) FILTER (WHERE state = 'idle in transaction')    AS idle_in_txn,
          count(*) FILTER (WHERE wait_event_type IS NOT NULL
                            AND state = 'active')                  AS waiting,
          count(*)                                                 AS total
        FROM pg_stat_activity WHERE datname = current_database()
      ),
      tbl AS (
        SELECT
          COALESCE(sum(n_dead_tup), 0)::bigint  AS dead_tup,
          COALESCE(sum(n_live_tup), 0)::bigint  AS live_tup,
          COALESCE(sum(seq_scan), 0)::bigint    AS seq_scans,
          COALESCE(sum(idx_scan), 0)::bigint    AS idx_scans
        FROM pg_stat_user_tables
      ),
      lck AS (
        SELECT
          count(*) FILTER (WHERE NOT granted)  AS blocked,
          count(*)                              AS total
        FROM pg_locks
      ),
      repl AS (
        SELECT COALESCE(
          EXTRACT(EPOCH FROM max(replay_lag))::bigint, 0
        ) AS lag
        FROM pg_stat_replication
      )
      SELECT
        conn.active, conn.idle, conn.idle_in_txn, conn.waiting, conn.total,
        d.xact_commit, d.xact_rollback,
        d.blks_read, d.blks_hit,
        d.deadlocks, d.temp_bytes,
        d.tup_returned, d.tup_fetched,
        d.tup_inserted, d.tup_updated, d.tup_deleted,
        pg_database_size(current_database()),
        (SELECT COALESCE(EXTRACT(EPOCH FROM max(clock_timestamp() - xact_start))::bigint, 0)
         FROM pg_stat_activity WHERE state != 'idle' AND xact_start IS NOT NULL),
        tbl.dead_tup, tbl.live_tup,
        tbl.seq_scans, tbl.idx_scans,
        lck.blocked, lck.total,
        repl.lag
      FROM pg_stat_database d, conn, tbl, lck, repl
      WHERE d.datname = current_database()
    """
      .query(
        int8 *: int8 *: int8 *: int8 *: int8 *:
          int8 *: int8 *:
          int8 *: int8 *:
          int8 *: int8 *:
          int8 *: int8 *:
          int8 *: int8 *: int8 *:
          int8 *:
          int8 *:
          int8 *: int8 *:
          int8 *: int8 *:
          int8 *: int8 *:
          int8
      )
      .to[SystemStats]

  private val businessQ: Query[Void, BusinessStats] =
    sql"""
      SELECT
        (SELECT count(*) FROM users),
        (SELECT count(*) FROM users WHERE last_login_at >= CURRENT_DATE),
        (SELECT count(*) FROM wallets),
        (SELECT count(*) FROM transactions WHERE status_id = 1),
        (SELECT count(*) FROM transactions WHERE status_id = 4 AND created_at >= CURRENT_DATE),
        (SELECT count(*) FROM transactions WHERE status_id = 3 AND completed_at >= CURRENT_DATE),
        (SELECT COALESCE(sum(amount), 0)::bigint FROM transactions
         WHERE status_id = 3 AND completed_at >= CURRENT_DATE),
        (SELECT COALESCE(avg(amount), 0)::bigint FROM transactions
         WHERE status_id = 3 AND completed_at >= CURRENT_DATE),
        (SELECT CASE WHEN COALESCE(sum(amount), 0) = 0
                     THEN 1::bigint ELSE 0::bigint END
         FROM ledger_entries)
    """
      .query(int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8)
      .to[BusinessStats]

  private val authQ: Query[Void, AuthStats] =
    sql"""
      SELECT
        (SELECT count(*) FROM auth.sessions WHERE is_active AND expires_at > now()),
        (SELECT count(*) FROM auth.sessions WHERE is_active AND expires_at <= now()),
        (SELECT count(DISTINCT user_id) FROM auth.sessions WHERE is_active),
        (SELECT count(*) FROM auth.login_attempts WHERE created_at > now() - INTERVAL '1 hour'),
        (SELECT count(*) FROM auth.login_attempts WHERE NOT success AND created_at > now() - INTERVAL '1 hour'),
        (SELECT COALESCE(
           ROUND(100.0 * count(*) FILTER (WHERE NOT success) / GREATEST(count(*), 1))::bigint, 0)
         FROM auth.login_attempts WHERE created_at > now() - INTERVAL '1 hour'),
        (SELECT count(DISTINCT ip_address) FROM auth.login_attempts WHERE NOT success AND created_at > now() - INTERVAL '1 hour'),
        (SELECT count(*) FROM auth.api_keys WHERE is_active),
        (SELECT count(*) FROM kyc_documents WHERE status_id = 2),
        (SELECT count(*) FROM users WHERE mfa_enabled)
    """
      .query(int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8)
      .to[AuthStats]

  // ── Instruments ────────────────────────────────────────────────────────

  private case class Instruments[F[_]](
    // connections
    connActive: GaugeRef[F],
    connIdle: GaugeRef[F],
    connIdleInTxn: GaugeRef[F],
    connWaiting: GaugeRef[F],
    connTotal: GaugeRef[F],
    // throughput
    cacheHitRatio: Histogram[F, Double],
    xactCommit: DeltaRef[F],
    xactRollback: DeltaRef[F],
    deadlocks: DeltaRef[F],
    tempBytes: DeltaRef[F],
    // row throughput
    tupReturned: DeltaRef[F],
    tupFetched: DeltaRef[F],
    tupInserted: DeltaRef[F],
    tupUpdated: DeltaRef[F],
    tupDeleted: DeltaRef[F],
    // storage & health
    dbSize: GaugeRef[F],
    oldestTxnAge: GaugeRef[F],
    deadTuples: GaugeRef[F],
    liveTuples: GaugeRef[F],
    tableBloatRatio: Histogram[F, Double],
    seqScans: DeltaRef[F],
    idxScans: DeltaRef[F],
    // locks & replication
    locksBlocked: GaugeRef[F],
    locksTotal: GaugeRef[F],
    replicationLag: GaugeRef[F],
    // business
    totalUsers: GaugeRef[F],
    activeUsersToday: GaugeRef[F],
    totalWallets: GaugeRef[F],
    pendingTxns: GaugeRef[F],
    failedTxnsToday: GaugeRef[F],
    completedTxnsToday: GaugeRef[F],
    volumeToday: GaugeRef[F],
    avgTxnSize: GaugeRef[F],
    ledgerBalanced: GaugeRef[F],
    // auth
    activeSessions: GaugeRef[F],
    expiredNotCleaned: GaugeRef[F],
    uniqueActiveUsers: GaugeRef[F],
    loginAttemptsLastHour: GaugeRef[F],
    failedLoginsLastHour: GaugeRef[F],
    failureRatePct: GaugeRef[F],
    distinctFailedIps: GaugeRef[F],
    activeApiKeys: GaugeRef[F],
    pendingKycDocs: GaugeRef[F],
    mfaEnabledUsers: GaugeRef[F]
  )

  private def mkGauge[F[_]: Temporal](
    name: String,
    unit: String,
    desc: String
  )(using m: Meter[F]): F[GaugeRef[F]] =
    for {
      c <- m.upDownCounter[Long](name).withUnit(unit).withDescription(desc).create
      r <- Temporal[F].ref(0L)
    } yield new GaugeRef(c, r)

  private def mkDelta[F[_]: Temporal](
    name: String,
    unit: String,
    desc: String
  )(using m: Meter[F]): F[DeltaRef[F]] =
    for {
      c <- m.counter[Long](name).withUnit(unit).withDescription(desc).create
      r <- Temporal[F].ref(0L)
    } yield new DeltaRef(c, r)

  private def makeInstruments[F[_]: Temporal](using m: Meter[F]): F[Instruments[F]] =
    for {
      // connections
      ca <- mkGauge("db.pg.connections.active", "{connections}", "Active DB connections")
      ci <- mkGauge("db.pg.connections.idle", "{connections}", "Idle DB connections")
      cit <- mkGauge(
               "db.pg.connections.idle_in_transaction",
               "{connections}",
               "Idle-in-txn DB connections"
             )
      cw <- mkGauge("db.pg.connections.waiting", "{connections}", "Connections waiting on locks")
      ct <- mkGauge("db.pg.connections.total", "{connections}", "Total DB connections")
      // throughput
      chr <- m.histogram[Double]("db.pg.cache_hit_ratio")
               .withUnit("1")
               .withDescription("Buffer cache hit ratio")
               .create
      xc <- mkDelta("db.pg.xact.commit", "{transactions}", "Committed transactions")
      xr <- mkDelta("db.pg.xact.rollback", "{transactions}", "Rolled-back transactions")
      dl <- mkDelta("db.pg.deadlocks", "{deadlocks}", "Deadlocks detected")
      tb <- mkDelta("db.pg.temp_bytes", "By", "Temp file bytes written (spilling to disk)")
      // row throughput
      tr <- mkDelta("db.pg.rows.returned", "{rows}", "Rows returned by queries")
      tf <- mkDelta("db.pg.rows.fetched", "{rows}", "Rows fetched (after filter)")
      ti <- mkDelta("db.pg.rows.inserted", "{rows}", "Rows inserted")
      tu <- mkDelta("db.pg.rows.updated", "{rows}", "Rows updated")
      td <- mkDelta("db.pg.rows.deleted", "{rows}", "Rows deleted")
      // storage & health
      ds  <- mkGauge("db.pg.database_size", "By", "Database size in bytes")
      ota <- mkGauge("db.pg.oldest_txn_age", "s", "Age of oldest running transaction")
      dt  <- mkGauge("db.pg.dead_tuples", "{tuples}", "Total dead tuples across tables")
      lt  <- mkGauge("db.pg.live_tuples", "{tuples}", "Total live tuples across tables")
      br <- m.histogram[Double]("db.pg.table_bloat_ratio")
              .withUnit("1")
              .withDescription("dead / (dead + live) tuples ratio")
              .create
      ss <- mkDelta("db.pg.seq_scans", "{scans}", "Sequential scans across tables")
      is <- mkDelta("db.pg.idx_scans", "{scans}", "Index scans across tables")
      // locks & replication
      lb  <- mkGauge("db.pg.locks.blocked", "{locks}", "Blocked lock requests")
      lt2 <- mkGauge("db.pg.locks.total", "{locks}", "Total lock count")
      rl  <- mkGauge("db.pg.replication_lag", "s", "Max replication replay lag in seconds")
      // business
      tus <- mkGauge("db.kudi.users.total", "{users}", "Total registered users")
      aut <- mkGauge("db.kudi.users.active_today", "{users}", "Users who logged in today")
      tw  <- mkGauge("db.kudi.wallets.total", "{wallets}", "Total wallets")
      pt  <- mkGauge("db.kudi.txns.pending", "{transactions}", "Pending transactions")
      ft  <- mkGauge("db.kudi.txns.failed_today", "{transactions}", "Failed transactions today")
      ctt <-
        mkGauge("db.kudi.txns.completed_today", "{transactions}", "Completed transactions today")
      vt <-
        mkGauge("db.kudi.volume.today", "{minor_units}", "Total money moved today (minor units)")
      avt <-
        mkGauge("db.kudi.volume.avg_txn_size", "{minor_units}", "Average transaction amount today")
      lbz <- mkGauge("db.kudi.ledger.balanced", "1", "1 if ledger balanced, 0 if not")
      // auth
      as <- mkGauge("db.kudi.sessions.active", "{sessions}", "Active sessions")
      enc <- mkGauge(
               "db.kudi.sessions.expired_not_cleaned",
               "{sessions}",
               "Expired sessions not yet cleaned"
             )
      uau <- mkGauge(
               "db.kudi.sessions.unique_active_users",
               "{users}",
               "Unique users with active sessions"
             )
      lah <-
        mkGauge("db.kudi.login.attempts_last_hour", "{attempts}", "Login attempts in the last hour")
      flh <-
        mkGauge("db.kudi.login.failed_last_hour", "{attempts}", "Failed logins in the last hour")
      frp <-
        mkGauge("db.kudi.login.failure_rate_pct", "%", "Login failure rate percentage (last hour)")
      dfi <- mkGauge(
               "db.kudi.login.distinct_failed_ips",
               "{ips}",
               "Distinct IPs with failed logins (last hour)"
             )
      aak <- mkGauge("db.kudi.api_keys.active", "{keys}", "Active API keys")
      pkd <- mkGauge("db.kudi.kyc.pending_docs", "{documents}", "Pending KYC documents")
      meu <- mkGauge("db.kudi.mfa.enabled_users", "{users}", "Users with MFA enabled")
    } yield Instruments(
      ca,
      ci,
      cit,
      cw,
      ct,
      chr,
      xc,
      xr,
      dl,
      tb,
      tr,
      tf,
      ti,
      tu,
      td,
      ds,
      ota,
      dt,
      lt,
      br,
      ss,
      is,
      lb,
      lt2,
      rl,
      tus,
      aut,
      tw,
      pt,
      ft,
      ctt,
      vt,
      avt,
      lbz,
      as,
      enc,
      uau,
      lah,
      flh,
      frp,
      dfi,
      aak,
      pkd,
      meu
    )

  // ── Poll ───────────────────────────────────────────────────────────────

  private def poll[F[_]: Temporal](s: Session[F], inst: Instruments[F]): F[Unit] =
    for {
      // System stats — single query with CTEs against pg_stat_database + pg_stat_activity + pg_stat_user_tables + pg_locks + pg_stat_replication
      sys <- s.execute(systemQ).map(_.head)
      _   <- inst.connActive.set(sys.connActive)
      _   <- inst.connIdle.set(sys.connIdle)
      _   <- inst.connIdleInTxn.set(sys.connIdleInTxn)
      _   <- inst.connWaiting.set(sys.connWaiting)
      _   <- inst.connTotal.set(sys.connTotal)
      ratio = if (sys.blksHit + sys.blksRead > 0)
                sys.blksHit.toDouble / (sys.blksHit + sys.blksRead).toDouble
              else 1.0
      _ <- inst.cacheHitRatio.record(ratio)
      _ <- inst.xactCommit.observe(sys.xactCommit)
      _ <- inst.xactRollback.observe(sys.xactRollback)
      _ <- inst.deadlocks.observe(sys.deadlocks)
      _ <- inst.tempBytes.observe(sys.tempBytes)
      _ <- inst.tupReturned.observe(sys.tupReturned)
      _ <- inst.tupFetched.observe(sys.tupFetched)
      _ <- inst.tupInserted.observe(sys.tupInserted)
      _ <- inst.tupUpdated.observe(sys.tupUpdated)
      _ <- inst.tupDeleted.observe(sys.tupDeleted)
      _ <- inst.dbSize.set(sys.dbSize)
      _ <- inst.oldestTxnAge.set(sys.oldestTxnAge)
      _ <- inst.deadTuples.set(sys.deadTuples)
      _ <- inst.liveTuples.set(sys.liveTuples)
      bloat = if (sys.liveTuples + sys.deadTuples > 0)
                sys.deadTuples.toDouble / (sys.liveTuples + sys.deadTuples).toDouble
              else 0.0
      _ <- inst.tableBloatRatio.record(bloat)
      _ <- inst.seqScans.observe(sys.seqScans)
      _ <- inst.idxScans.observe(sys.idxScans)
      _ <- inst.locksBlocked.set(sys.locksBlocked)
      _ <- inst.locksTotal.set(sys.locksTotal)
      _ <- inst.replicationLag.set(sys.replicationLag)
      // Business stats — scalar subqueries against app tables
      biz <- s.execute(businessQ).map(_.head)
      _   <- inst.totalUsers.set(biz.users)
      _   <- inst.activeUsersToday.set(biz.activeUsersToday)
      _   <- inst.totalWallets.set(biz.wallets)
      _   <- inst.pendingTxns.set(biz.pendingTxns)
      _   <- inst.failedTxnsToday.set(biz.failedTxnsToday)
      _   <- inst.completedTxnsToday.set(biz.completedTxnsToday)
      _   <- inst.volumeToday.set(biz.volumeToday)
      _   <- inst.avgTxnSize.set(biz.avgTxnSize)
      _   <- inst.ledgerBalanced.set(biz.ledgerBalanced)
      // Auth stats — scalar subqueries against auth schema tables
      auth <- s.execute(authQ).map(_.head)
      _    <- inst.activeSessions.set(auth.activeSessions)
      _    <- inst.expiredNotCleaned.set(auth.expiredNotCleaned)
      _    <- inst.uniqueActiveUsers.set(auth.uniqueActiveUsers)
      _    <- inst.loginAttemptsLastHour.set(auth.loginAttemptsLastHour)
      _    <- inst.failedLoginsLastHour.set(auth.failedLoginsLastHour)
      _    <- inst.failureRatePct.set(auth.failureRatePct)
      _    <- inst.distinctFailedIps.set(auth.distinctFailedIps)
      _    <- inst.activeApiKeys.set(auth.activeApiKeys)
      _    <- inst.pendingKycDocs.set(auth.pendingKycDocs)
      _    <- inst.mfaEnabledUsers.set(auth.mfaEnabledUsers)
    } yield ()

  // ── Public API ─────────────────────────────────────────────────────────

  /**
    * Starts a background fiber that polls PG stats every `interval` and records them as otel4s
    * metrics. Acquires one session from `pool` per poll cycle. Errors are silently swallowed so the
    * collector never crashes the app (stale metrics are observable from the metrics themselves).
    */
  def make[F[_]: Temporal](
    pool: Resource[F, Session[F]],
    interval: FiniteDuration = 15.seconds
  )(using Meter[F]): Resource[F, Unit] =
    for {
      inst <- Resource.eval(makeInstruments[F])
      _ <- Stream
             .fixedDelay[F](interval)
             .evalMap(_ => pool.use(s => poll(s, inst)).attempt.void)
             .compile
             .drain
             .background
             .void
    } yield ()

}
