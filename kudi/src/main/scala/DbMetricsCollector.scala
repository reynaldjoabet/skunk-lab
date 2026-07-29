package kudi

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.kernel.syntax.all.*
import cats.syntax.all.*
import cats.Monad
import cats.MonadThrow
import fs2.Stream

import org.typelevel.log4cats.Logger
import org.typelevel.otel4s.metrics.*
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

object DbMetricsCollector {

  final case class Config(
    interval: FiniteDuration = 15.seconds,
    pollTimeout: FiniteDuration = 10.seconds,
    collectBusinessMetrics: Boolean = true,
    collectAuthMetrics: Boolean = true
  )

  final private case class LongGauge[F[_]](
    ref: Ref[F, Long]
  ) {

    def set(value: Long): F[Unit] =
      ref.set(value)

  }

  final private case class DoubleGauge[F[_]](
    ref: Ref[F, Double]
  ) {

    def set(value: Double): F[Unit] =
      ref.set(sanitizeDouble(value))

  }

  final private class DeltaCounter[F[_]: Monad](
    counter: Counter[F, Long],
    previous: Ref[F, Option[Long]]
  ) {

    def observe(cumulative: Long): F[Unit] =
      previous
        .modify {
          case None =>
            Some(cumulative) -> Monad[F].unit

          case Some(prev) if cumulative >= prev =>
            val delta = cumulative - prev

            Some(cumulative) ->
              (if (delta > 0L) counter.add(delta) else Monad[F].unit)

          case Some(_) =>
            // PostgreSQL stats may reset after pg_stat_reset(), restart, failover, or restore.
            // Do not emit a negative delta; just re-seed the baseline.
            Some(cumulative) -> Monad[F].unit
        }
        .flatten

  }

  private def sanitizeDouble(value: Double): Double =
    if (value.isNaN || value.isInfinity) 0.0 else value

  private def epochSeconds[F[_]: Temporal]: F[Long] =
    Temporal[F].realTime.map(_.toSeconds)

  private def mkLongGauge[F[_]: Temporal](
    name: String,
    unit: String,
    description: String
  )(using meter: Meter[F]): Resource[F, LongGauge[F]] =
    for {
      ref <- Resource.eval(Ref.of[F, Long](0L))
      _   <- meter
             .observableGauge[Double](name)
             .withUnit(unit)
             .withDescription(description)
             .createWithCallback { callback =>
               ref.get.flatMap(value => callback.record(value.toDouble))
             }
    } yield LongGauge(ref)

  private def mkDoubleGauge[F[_]: Temporal](
    name: String,
    unit: String,
    description: String
  )(using meter: Meter[F]): Resource[F, DoubleGauge[F]] =
    for {
      ref <- Resource.eval(Ref.of[F, Double](0.0))
      _   <- meter
             .observableGauge[Double](name)
             .withUnit(unit)
             .withDescription(description)
             .createWithCallback { callback =>
               ref.get.flatMap(value => callback.record(value))
             }
    } yield DoubleGauge(ref)

  private def mkCounter[F[_]: Temporal](
    name: String,
    unit: String,
    description: String
  )(using meter: Meter[F]): Resource[F, Counter[F, Long]] =
    Resource.eval {
      meter.counter[Long](name).withUnit(unit).withDescription(description).create
    }

  private def mkHistogram[F[_]: Temporal](
    name: String,
    unit: String,
    description: String
  )(using meter: Meter[F]): Resource[F, Histogram[F, Double]] =
    Resource.eval {
      meter.histogram[Double](name).withUnit(unit).withDescription(description).create
    }

  private def mkDeltaCounter[F[_]: Temporal](
    name: String,
    unit: String,
    description: String
  )(using meter: Meter[F]): Resource[F, DeltaCounter[F]] =
    for {
      counter  <- mkCounter[F](name, unit, description)
      previous <- Resource.eval(Ref.of[F, Option[Long]](None))
    } yield new DeltaCounter[F](counter, previous)

  final private case class SystemStats(
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

  final private case class BusinessStats(
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

  final private case class AuthStats(
    activeSessions: Long,
    expiredNotCleaned: Long,
    uniqueActiveUsers: Long,
    loginAttemptsLastHour: Long,
    failedLoginsLastHour: Long,
    failureRate: Double,
    distinctFailedIps: Long,
    activeApiKeys: Long,
    pendingKycDocs: Long,
    mfaEnabledUsers: Long
  )

  private val systemQ: Query[Void, SystemStats] =
    sql"""
      WITH conn AS (
        SELECT
          count(*) FILTER (WHERE state = 'active')              AS active,
          count(*) FILTER (WHERE state = 'idle')                AS idle,
          count(*) FILTER (WHERE state = 'idle in transaction') AS idle_in_txn,
          count(*) FILTER (
            WHERE wait_event_type IS NOT NULL
              AND state = 'active'
          )                                                     AS waiting,
          count(*)                                              AS total
        FROM pg_stat_activity
        WHERE datname = current_database()
      ),
      tbl AS (
        SELECT
          COALESCE(sum(n_dead_tup), 0)::bigint AS dead_tup,
          COALESCE(sum(n_live_tup), 0)::bigint AS live_tup,
          COALESCE(sum(seq_scan), 0)::bigint   AS seq_scans,
          COALESCE(sum(idx_scan), 0)::bigint   AS idx_scans
        FROM pg_stat_user_tables
      ),
      lck AS (
        SELECT
          count(*) FILTER (WHERE NOT granted) AS blocked,
          count(*)                            AS total
        FROM pg_locks
      ),
      repl AS (
        SELECT
          COALESCE(EXTRACT(EPOCH FROM max(replay_lag))::bigint, 0) AS lag
        FROM pg_stat_replication
      )
      SELECT
        conn.active,
        conn.idle,
        conn.idle_in_txn,
        conn.waiting,
        conn.total,
        d.xact_commit,
        d.xact_rollback,
        d.blks_read,
        d.blks_hit,
        d.deadlocks,
        d.temp_bytes,
        d.tup_returned,
        d.tup_fetched,
        d.tup_inserted,
        d.tup_updated,
        d.tup_deleted,
        pg_database_size(current_database()),
        (
          SELECT COALESCE(
            EXTRACT(EPOCH FROM max(clock_timestamp() - xact_start))::bigint,
            0
          )
          FROM pg_stat_activity
          WHERE state != 'idle'
            AND xact_start IS NOT NULL
            AND datname = current_database()
        ),
        tbl.dead_tup,
        tbl.live_tup,
        tbl.seq_scans,
        tbl.idx_scans,
        lck.blocked,
        lck.total,
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
        (
          SELECT COALESCE(sum(amount), 0)::bigint
          FROM transactions
          WHERE status_id = 3
            AND completed_at >= CURRENT_DATE
        ),
        (
          SELECT COALESCE(avg(amount), 0)::bigint
          FROM transactions
          WHERE status_id = 3
            AND completed_at >= CURRENT_DATE
        ),
        (
          SELECT CASE
            WHEN COALESCE(sum(amount), 0) = 0 THEN 1::bigint
            ELSE 0::bigint
          END
          FROM ledger_entries
        )
    """
      .query(int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8 *: int8)
      .to[BusinessStats]

  private val authQ: Query[Void, AuthStats] =
    sql"""
      SELECT
        (SELECT count(*) FROM auth.sessions WHERE is_active AND expires_at > now()),
        (SELECT count(*) FROM auth.sessions WHERE is_active AND expires_at <= now()),
        (SELECT count(DISTINCT user_id) FROM auth.sessions WHERE is_active),
        (
          SELECT count(*)
          FROM auth.login_attempts
          WHERE created_at > now() - INTERVAL '1 hour'
        ),
        (
          SELECT count(*)
          FROM auth.login_attempts
          WHERE NOT success
            AND created_at > now() - INTERVAL '1 hour'
        ),
        (
          SELECT COALESCE(
            (
              (count(*) FILTER (WHERE NOT success))::float8 /
              GREATEST(count(*)::float8, 1.0::float8)
            ),
            0.0::float8
          )
          FROM auth.login_attempts
          WHERE created_at > now() - INTERVAL '1 hour'
        ),
        (
          SELECT count(DISTINCT ip_address)
          FROM auth.login_attempts
          WHERE NOT success
            AND created_at > now() - INTERVAL '1 hour'
        ),
        (SELECT count(*) FROM auth.api_keys WHERE is_active),
        (SELECT count(*) FROM kyc_documents WHERE status_id = 2),
        (SELECT count(*) FROM users WHERE mfa_enabled)
    """
      .query(int8 *: int8 *: int8 *: int8 *: int8 *: float8 *: int8 *: int8 *: int8 *: int8)
      .to[AuthStats]

  final private case class PgInstruments[F[_]](
    connActive: LongGauge[F],
    connIdle: LongGauge[F],
    connIdleInTxn: LongGauge[F],
    connWaiting: LongGauge[F],
    connTotal: LongGauge[F],
    cacheHitRatio: DoubleGauge[F],
    xactCommit: DeltaCounter[F],
    xactRollback: DeltaCounter[F],
    deadlocks: DeltaCounter[F],
    tempBytes: DeltaCounter[F],
    tupReturned: DeltaCounter[F],
    tupFetched: DeltaCounter[F],
    tupInserted: DeltaCounter[F],
    tupUpdated: DeltaCounter[F],
    tupDeleted: DeltaCounter[F],
    dbSize: LongGauge[F],
    oldestTxnAge: LongGauge[F],
    deadTuples: LongGauge[F],
    liveTuples: LongGauge[F],
    deadTupleRatio: DoubleGauge[F],
    seqScans: DeltaCounter[F],
    idxScans: DeltaCounter[F],
    locksBlocked: LongGauge[F],
    locksTotal: LongGauge[F],
    replicationLag: LongGauge[F]
  )

  final private case class BusinessInstruments[F[_]](
    totalUsers: LongGauge[F],
    activeUsersToday: LongGauge[F],
    totalWallets: LongGauge[F],
    pendingTxns: LongGauge[F],
    failedTxnsToday: LongGauge[F],
    completedTxnsToday: LongGauge[F],
    volumeToday: LongGauge[F],
    avgTxnSize: LongGauge[F],
    ledgerBalanced: LongGauge[F]
  )

  final private case class AuthInstruments[F[_]](
    activeSessions: LongGauge[F],
    expiredNotCleaned: LongGauge[F],
    uniqueActiveUsers: LongGauge[F],
    loginAttemptsLastHour: LongGauge[F],
    failedLoginsLastHour: LongGauge[F],
    failureRate: DoubleGauge[F],
    distinctFailedIps: LongGauge[F],
    activeApiKeys: LongGauge[F],
    pendingKycDocs: LongGauge[F],
    mfaEnabledUsers: LongGauge[F]
  )

  final private case class CollectorInstruments[F[_]](
    up: LongGauge[F],
    lastSuccessEpoch: LongGauge[F],
    lastFailureEpoch: LongGauge[F],
    pollDuration: Histogram[F, Double],
    totalFailures: Counter[F, Long],
    systemFailures: Counter[F, Long],
    businessFailures: Counter[F, Long],
    authFailures: Counter[F, Long],
    sessionFailures: Counter[F, Long],
    pollTimeouts: Counter[F, Long]
  )

  final private case class Instruments[F[_]](
    pg: PgInstruments[F],
    business: BusinessInstruments[F],
    auth: AuthInstruments[F],
    collector: CollectorInstruments[F]
  )

  private def makePgInstruments[F[_]: Temporal](using Meter[F]): Resource[F, PgInstruments[F]] =
    for {
      connActive <-
        mkLongGauge("db.pg.connections.active", "{connections}", "Active PostgreSQL connections")
      connIdle <-
        mkLongGauge("db.pg.connections.idle", "{connections}", "Idle PostgreSQL connections")
      connIdleInTxn <- mkLongGauge(
                         "db.pg.connections.idle_in_transaction",
                         "{connections}",
                         "PostgreSQL connections idle in transaction"
                       )
      connWaiting <- mkLongGauge(
                       "db.pg.connections.waiting",
                       "{connections}",
                       "Active PostgreSQL connections waiting on an event"
                     )
      connTotal <-
        mkLongGauge("db.pg.connections.total", "{connections}", "Total PostgreSQL connections")

      cacheHitRatio <-
        mkDoubleGauge("db.pg.cache_hit_ratio", "1", "PostgreSQL buffer cache hit ratio")

      xactCommit <-
        mkDeltaCounter("db.pg.xact.commit", "{transactions}", "Committed PostgreSQL transactions")
      xactRollback <- mkDeltaCounter(
                        "db.pg.xact.rollback",
                        "{transactions}",
                        "Rolled-back PostgreSQL transactions"
                      )
      deadlocks <- mkDeltaCounter("db.pg.deadlocks", "{deadlocks}", "PostgreSQL deadlocks")
      tempBytes <- mkDeltaCounter("db.pg.temp_bytes", "By", "PostgreSQL temp bytes written")

      tupReturned <- mkDeltaCounter("db.pg.rows.returned", "{rows}", "Rows returned by PostgreSQL")
      tupFetched  <- mkDeltaCounter("db.pg.rows.fetched", "{rows}", "Rows fetched by PostgreSQL")
      tupInserted <- mkDeltaCounter("db.pg.rows.inserted", "{rows}", "Rows inserted by PostgreSQL")
      tupUpdated  <- mkDeltaCounter("db.pg.rows.updated", "{rows}", "Rows updated by PostgreSQL")
      tupDeleted  <- mkDeltaCounter("db.pg.rows.deleted", "{rows}", "Rows deleted by PostgreSQL")

      dbSize       <- mkLongGauge("db.pg.database_size", "By", "Current PostgreSQL database size")
      oldestTxnAge <-
        mkLongGauge("db.pg.oldest_txn_age", "s", "Age of the oldest open PostgreSQL transaction")
      deadTuples <-
        mkLongGauge("db.pg.dead_tuples", "{tuples}", "Estimated dead tuples across user tables")
      liveTuples <-
        mkLongGauge("db.pg.live_tuples", "{tuples}", "Estimated live tuples across user tables")
      deadTupleRatio <-
        mkDoubleGauge("db.pg.dead_tuple_ratio", "1", "dead_tuples / (dead_tuples + live_tuples)")

      seqScans <- mkDeltaCounter(
                    "db.pg.seq_scans",
                    "{scans}",
                    "Sequential scans across PostgreSQL user tables"
                  )
      idxScans <-
        mkDeltaCounter("db.pg.idx_scans", "{scans}", "Index scans across PostgreSQL user tables")

      locksBlocked <-
        mkLongGauge("db.pg.locks.blocked", "{locks}", "PostgreSQL lock requests not yet granted")
      locksTotal     <- mkLongGauge("db.pg.locks.total", "{locks}", "Total PostgreSQL lock records")
      replicationLag <-
        mkLongGauge("db.pg.replication_lag", "s", "Maximum PostgreSQL replication replay lag")
    } yield PgInstruments(
      connActive,
      connIdle,
      connIdleInTxn,
      connWaiting,
      connTotal,
      cacheHitRatio,
      xactCommit,
      xactRollback,
      deadlocks,
      tempBytes,
      tupReturned,
      tupFetched,
      tupInserted,
      tupUpdated,
      tupDeleted,
      dbSize,
      oldestTxnAge,
      deadTuples,
      liveTuples,
      deadTupleRatio,
      seqScans,
      idxScans,
      locksBlocked,
      locksTotal,
      replicationLag
    )

  private def makeBusinessInstruments[F[_]: Temporal](using
    Meter[F]
  ): Resource[F, BusinessInstruments[F]] =
    for {
      totalUsers       <- mkLongGauge("db.kudi.users.total", "{users}", "Total registered users")
      activeUsersToday <- mkLongGauge("db.kudi.users.active_today", "{users}", "Users active today")
      totalWallets     <- mkLongGauge("db.kudi.wallets.total", "{wallets}", "Total wallets")
      pendingTxns      <- mkLongGauge("db.kudi.txns.pending", "{transactions}", "Pending transactions")
      failedTxnsToday  <-
        mkLongGauge("db.kudi.txns.failed_today", "{transactions}", "Failed transactions today")
      completedTxnsToday <- mkLongGauge(
                              "db.kudi.txns.completed_today",
                              "{transactions}",
                              "Completed transactions today"
                            )
      volumeToday <- mkLongGauge(
                       "db.kudi.volume.today",
                       "{minor_units}",
                       "Total completed transaction volume today"
                     )
      avgTxnSize <- mkLongGauge(
                      "db.kudi.volume.avg_txn_size",
                      "{minor_units}",
                      "Average completed transaction amount today"
                    )
      ledgerBalanced <- mkLongGauge(
                          "db.kudi.ledger.balanced",
                          "1",
                          "1 when ledger entries sum to zero, otherwise 0"
                        )
    } yield BusinessInstruments(
      totalUsers,
      activeUsersToday,
      totalWallets,
      pendingTxns,
      failedTxnsToday,
      completedTxnsToday,
      volumeToday,
      avgTxnSize,
      ledgerBalanced
    )

  private def makeAuthInstruments[F[_]: Temporal](using Meter[F]): Resource[F, AuthInstruments[F]] =
    for {
      activeSessions    <- mkLongGauge("db.kudi.sessions.active", "{sessions}", "Active sessions")
      expiredNotCleaned <- mkLongGauge(
                             "db.kudi.sessions.expired_not_cleaned",
                             "{sessions}",
                             "Expired active sessions not yet cleaned"
                           )
      uniqueActiveUsers <- mkLongGauge(
                             "db.kudi.sessions.unique_active_users",
                             "{users}",
                             "Unique users with active sessions"
                           )
      loginAttemptsLastHour <- mkLongGauge(
                                 "db.kudi.login.attempts_last_hour",
                                 "{attempts}",
                                 "Login attempts in the last hour"
                               )
      failedLoginsLastHour <- mkLongGauge(
                                "db.kudi.login.failed_last_hour",
                                "{attempts}",
                                "Failed login attempts in the last hour"
                              )
      failureRate <-
        mkDoubleGauge("db.kudi.login.failure_rate", "1", "Login failure ratio in the last hour")
      distinctFailedIps <- mkLongGauge(
                             "db.kudi.login.distinct_failed_ips",
                             "{ips}",
                             "Distinct IPs with failed logins in the last hour"
                           )
      activeApiKeys  <- mkLongGauge("db.kudi.api_keys.active", "{keys}", "Active API keys")
      pendingKycDocs <-
        mkLongGauge("db.kudi.kyc.pending_docs", "{documents}", "Pending KYC documents")
      mfaEnabledUsers <-
        mkLongGauge("db.kudi.mfa.enabled_users", "{users}", "Users with MFA enabled")
    } yield AuthInstruments(
      activeSessions,
      expiredNotCleaned,
      uniqueActiveUsers,
      loginAttemptsLastHour,
      failedLoginsLastHour,
      failureRate,
      distinctFailedIps,
      activeApiKeys,
      pendingKycDocs,
      mfaEnabledUsers
    )

  private def makeCollectorInstruments[F[_]: Temporal](using
    Meter[F]
  ): Resource[F, CollectorInstruments[F]] =
    for {
      up <- mkLongGauge(
              "db.kudi.collector.up",
              "1",
              "1 if the latest DB metrics poll succeeded, otherwise 0"
            )
      lastSuccessEpoch <- mkLongGauge(
                            "db.kudi.collector.last_success_epoch",
                            "s",
                            "Unix epoch seconds of the latest successful poll"
                          )
      lastFailureEpoch <- mkLongGauge(
                            "db.kudi.collector.last_failure_epoch",
                            "s",
                            "Unix epoch seconds of the latest failed poll"
                          )
      pollDuration <-
        mkHistogram("db.kudi.collector.poll_duration", "s", "DB metrics poll duration")
      totalFailures <-
        mkCounter("db.kudi.collector.failures", "{failures}", "Total DB metrics collector failures")
      systemFailures <-
        mkCounter("db.kudi.collector.system_failures", "{failures}", "System metrics poll failures")
      businessFailures <- mkCounter(
                            "db.kudi.collector.business_failures",
                            "{failures}",
                            "Business metrics poll failures"
                          )
      authFailures <-
        mkCounter("db.kudi.collector.auth_failures", "{failures}", "Auth metrics poll failures")
      sessionFailures <- mkCounter(
                           "db.kudi.collector.session_failures",
                           "{failures}",
                           "DB metrics session acquisition failures"
                         )
      pollTimeouts <-
        mkCounter("db.kudi.collector.timeouts", "{timeouts}", "DB metrics poll timeouts")
    } yield CollectorInstruments(
      up,
      lastSuccessEpoch,
      lastFailureEpoch,
      pollDuration,
      totalFailures,
      systemFailures,
      businessFailures,
      authFailures,
      sessionFailures,
      pollTimeouts
    )

  private def makeInstruments[F[_]: Temporal](using Meter[F]): Resource[F, Instruments[F]] =
    for {
      pg        <- makePgInstruments[F]
      business  <- makeBusinessInstruments[F]
      auth      <- makeAuthInstruments[F]
      collector <- makeCollectorInstruments[F]
    } yield Instruments(pg, business, auth, collector)

  private def uniqueOne[F[_]: MonadThrow, A](
    session: Session[F],
    query: Query[Void, A],
    queryName: String
  ): F[A] =
    session
      .execute(query)
      .flatMap {
        case row :: Nil =>
          row.pure[F]

        case Nil =>
          new NoSuchElementException(s"$queryName returned no rows").raiseError[F, A]

        case rows =>
          new IllegalStateException(s"$queryName returned ${rows.size} rows").raiseError[F, A]
      }

  private def pollSystem[F[_]: MonadThrow](
    session: Session[F],
    inst: PgInstruments[F]
  ): F[Unit] =
    for {
      sys <- uniqueOne(session, systemQ, "system metrics query")

      totalBlocks   = sys.blksHit.toDouble + sys.blksRead.toDouble
      cacheHitRatio =
        if (totalBlocks > 0.0) sys.blksHit.toDouble / totalBlocks
        else 1.0

      totalTuples    = sys.liveTuples.toDouble + sys.deadTuples.toDouble
      deadTupleRatio =
        if (totalTuples > 0.0) sys.deadTuples.toDouble / totalTuples
        else 0.0

      _ <- inst.connActive.set(sys.connActive)
      _ <- inst.connIdle.set(sys.connIdle)
      _ <- inst.connIdleInTxn.set(sys.connIdleInTxn)
      _ <- inst.connWaiting.set(sys.connWaiting)
      _ <- inst.connTotal.set(sys.connTotal)

      _ <- inst.cacheHitRatio.set(cacheHitRatio)

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
      _ <- inst.deadTupleRatio.set(deadTupleRatio)

      _ <- inst.seqScans.observe(sys.seqScans)
      _ <- inst.idxScans.observe(sys.idxScans)

      _ <- inst.locksBlocked.set(sys.locksBlocked)
      _ <- inst.locksTotal.set(sys.locksTotal)
      _ <- inst.replicationLag.set(sys.replicationLag)
    } yield ()

  private def pollBusiness[F[_]: MonadThrow](
    session: Session[F],
    inst: BusinessInstruments[F]
  ): F[Unit] =
    for {
      biz <- uniqueOne(session, businessQ, "business metrics query")

      _ <- inst.totalUsers.set(biz.users)
      _ <- inst.activeUsersToday.set(biz.activeUsersToday)
      _ <- inst.totalWallets.set(biz.wallets)
      _ <- inst.pendingTxns.set(biz.pendingTxns)
      _ <- inst.failedTxnsToday.set(biz.failedTxnsToday)
      _ <- inst.completedTxnsToday.set(biz.completedTxnsToday)
      _ <- inst.volumeToday.set(biz.volumeToday)
      _ <- inst.avgTxnSize.set(biz.avgTxnSize)
      _ <- inst.ledgerBalanced.set(biz.ledgerBalanced)
    } yield ()

  private def pollAuth[F[_]: MonadThrow](
    session: Session[F],
    inst: AuthInstruments[F]
  ): F[Unit] =
    for {
      auth <- uniqueOne(session, authQ, "auth metrics query")

      _ <- inst.activeSessions.set(auth.activeSessions)
      _ <- inst.expiredNotCleaned.set(auth.expiredNotCleaned)
      _ <- inst.uniqueActiveUsers.set(auth.uniqueActiveUsers)
      _ <- inst.loginAttemptsLastHour.set(auth.loginAttemptsLastHour)
      _ <- inst.failedLoginsLastHour.set(auth.failedLoginsLastHour)
      _ <- inst.failureRate.set(auth.failureRate)
      _ <- inst.distinctFailedIps.set(auth.distinctFailedIps)
      _ <- inst.activeApiKeys.set(auth.activeApiKeys)
      _ <- inst.pendingKycDocs.set(auth.pendingKycDocs)
      _ <- inst.mfaEnabledUsers.set(auth.mfaEnabledUsers)
    } yield ()

  private def runSection[F[_]: Temporal: Logger](
    name: String,
    failures: Counter[F, Long]
  )(effect: F[Unit]): F[Boolean] =
    effect
      .as(true)
      .handleErrorWith { error =>
        failures.inc() *>
          Logger[F].warn(error)(s"DB metrics collector section failed: $name").as(false)
      }

  private def collectWithSession[F[_]: Temporal: Logger](
    session: Session[F],
    inst: Instruments[F],
    config: Config
  ): F[Unit] = {
    val sections: F[Boolean] =
      for {
        systemOk <- runSection("system", inst.collector.systemFailures) {
                      pollSystem(session, inst.pg)
                    }

        businessOk <-
          if (config.collectBusinessMetrics)
            runSection("business", inst.collector.businessFailures) {
              pollBusiness(session, inst.business)
            }
          else true.pure[F]

        authOk <-
          if (config.collectAuthMetrics)
            runSection("auth", inst.collector.authFailures) {
              pollAuth(session, inst.auth)
            }
          else true.pure[F]
      } yield systemOk && businessOk && authOk

    for {
      startedAt <- Temporal[F].monotonic

      ok <- sections.timeoutTo(
              config.pollTimeout,
              Logger[F].warn(s"DB metrics collector poll timed out after ${config.pollTimeout}") *>
                inst.collector.pollTimeouts.inc().as(false)
            )

      finishedAt     <- Temporal[F].monotonic
      durationSeconds = (finishedAt - startedAt).toNanos.toDouble / 1000000000.0

      _ <- inst.collector.pollDuration.record(durationSeconds)

      now <- epochSeconds[F]

      _ <-
        if (ok)
          inst.collector.up.set(1L) *>
            inst.collector.lastSuccessEpoch.set(now)
        else
          inst.collector.totalFailures.inc() *>
            inst.collector.up.set(0L) *>
            inst.collector.lastFailureEpoch.set(now)
    } yield ()
  }

  private def collectOnce[F[_]: Temporal: Logger](
    pool: Resource[F, Session[F]],
    inst: Instruments[F],
    config: Config
  ): F[Unit] =
    pool
      .use(session => collectWithSession(session, inst, config))
      .handleErrorWith { error =>
        for {
          _   <- Logger[F].warn(error)("DB metrics collector failed before poll sections completed")
          _   <- inst.collector.sessionFailures.inc()
          _   <- inst.collector.totalFailures.inc()
          now <- epochSeconds[F]
          _   <- inst.collector.up.set(0L)
          _   <- inst.collector.lastFailureEpoch.set(now)
        } yield ()
      }

  private def validateConfig[F[_]: MonadThrow](config: Config): F[Unit] =
    if (config.interval <= 0.seconds)
      new IllegalArgumentException("DbMetricsCollector interval must be positive").raiseError[
        F,
        Unit
      ]
    else if (config.pollTimeout <= 0.seconds)
      new IllegalArgumentException("DbMetricsCollector pollTimeout must be positive").raiseError[
        F,
        Unit
      ]
    else ().pure[F]

  def make[F[_]: Temporal: Logger](
    pool: Resource[F, Session[F]],
    interval: FiniteDuration = 15.seconds
  )(using Meter[F]): Resource[F, Unit] =
    make(pool, Config(interval = interval))

  def make[F[_]: Temporal: Logger](
    pool: Resource[F, Session[F]],
    config: Config
  )(using Meter[F]): Resource[F, Unit] =
    for {
      _    <- Resource.eval(validateConfig(config))
      inst <- makeInstruments[F]

      _ <- (
             Stream.eval(collectOnce(pool, inst, config)) ++
               Stream.fixedDelay[F](config.interval).evalMap(_ => collectOnce(pool, inst, config))
           ).compile.drain.background.void
    } yield ()

}
