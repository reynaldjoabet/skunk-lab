package idmgmt

import scala.concurrent.duration.*

import cats.effect.*
import cats.effect.std.Console
import cats.syntax.all.*
import cats.Monad
import fs2.io.net.Network

import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.metrics.Histogram
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.UpDownCounter
import org.typelevel.otel4s.trace.StatusCode
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*

// ── Configuration ──────────────────────────────────────────────────────

case class DbConfig(
  primaryHost: String,
  replicaHost: String,
  port: Int,
  user: String,
  password: String,
  database: String,
  writerPoolSize: Int,
  readerPoolSize: Int,
  writerReadTimeout: Duration,
  readerReadTimeout: Duration,
  writerStatementTimeout: Int,
  readerStatementTimeout: Int,
  idleInTransactionTimeout: Int,
  lockTimeout: Int,
  tcpKeepalivesIdle: Int,
  tcpKeepalivesInterval: Int,
  tcpKeepalivesCount: Int,
  ssl: String,
  applicationName: String,
  workMem: String,
  commandCacheSize: Int,
  queryCacheSize: Int,
  parseCacheSize: Int
)

object DbConfig {

  def resolveSSL(value: String): SSL =
    value.toLowerCase match {
      case "system"  => SSL.System
      case "trusted" => SSL.Trusted
      case "none"    => SSL.None
      case other     => throw new IllegalArgumentException(s"Unknown SSL mode: $other")
    }

}

case class AppConfig(db: DbConfig)

// ── Pool metrics ───────────────────────────────────────────────────────

case class PoolMetrics[F[_]](
  checkedOut: UpDownCounter[F, Long],
  acquisitionTime: Histogram[F, Double],
  errors: Counter[F, Long]
)

object PoolMetrics {

  def make[F[_]: Monad](using m: Meter[F]): F[PoolMetrics[F]] =
    for {
      co <- m.upDownCounter[Long]("db.pool.checked_out")
              .withUnit("{connections}")
              .withDescription("Sessions currently checked out from the pool")
              .create
      at <- m.histogram[Double]("db.pool.acquisition_time")
              .withUnit("ms")
              .withDescription("Time to acquire a session from the pool")
              .create
      er <- m.counter[Long]("db.pool.errors")
              .withUnit("{errors}")
              .withDescription("Session acquisition or usage errors")
              .create
    } yield PoolMetrics(co, at, er)

  def instrument[F[_]: Temporal: Tracer](
    pool: Resource[F, Session[F]],
    metrics: PoolMetrics[F],
    poolAttr: Attribute[String]
  ): Resource[F, Session[F]] =
    Resource
      .eval(Clock[F].monotonic)
      .flatMap { start =>
        pool
          .evalTap { _ =>
            for {
              end    <- Clock[F].monotonic
              elapsed = (end - start).toUnit(MILLISECONDS)
              _      <- metrics.acquisitionTime.record(elapsed, poolAttr)
              _      <- metrics.checkedOut.add(1L, poolAttr)
              _ <- Tracer[F]
                     .currentSpanOrNoop
                     .flatMap(
                       _.addAttribute(Attribute("db.pool.acquisition_time_ms", elapsed))
                     )
            } yield ()
          }
          .onFinalize(metrics.checkedOut.add(-1L, poolAttr))
          .handleErrorWith { (err: Throwable) =>
            Resource.eval(
              metrics.errors.add(1L, poolAttr) *>
                Tracer[F]
                  .currentSpanOrNoop
                  .flatMap { span =>
                    span.addAttribute(Attribute("error", true)) *>
                      span.setStatus(StatusCode.Error, err.getMessage)
                  }
            ) *> Resource.raiseError[F, Session[F], Throwable](err)
          }
      }

}

// ── Pool construction ──────────────────────────────────────────────────

case class DbPools[F[_]](
  writer: Resource[F, Session[F]],
  reader: Resource[F, Session[F]]
)

object DbPools {

  def make[F[_]: Temporal: Tracer: Meter: Console: Network](
    cfg: AppConfig
  ): Resource[F, DbPools[F]] = {
    val db  = cfg.db
    val ssl = DbConfig.resolveSSL(db.ssl)

    val commonParams = Session.DefaultConnectionParameters ++ Map(
      "application_name"                    -> db.applicationName,
      "idle_in_transaction_session_timeout" -> db.idleInTransactionTimeout.toString,
      "tcp_keepalives_idle"                 -> db.tcpKeepalivesIdle.toString,
      "tcp_keepalives_interval"             -> db.tcpKeepalivesInterval.toString,
      "tcp_keepalives_count"                -> db.tcpKeepalivesCount.toString,
      "work_mem"                            -> db.workMem,
      "default_transaction_isolation"       -> "read committed",
      "plan_cache_mode"                     -> "force_generic_plan",
      "jit"                                 -> "off"
    )

    def baseBuilder: Session.Builder[F] =
      Session
        .Builder[F]
        .withPort(db.port)
        .withUserAndPassword(db.user, db.password)
        .withDatabase(db.database)
        .withSSL(ssl)
        .withRedactionStrategy(RedactionStrategy.All)
        .withTypingStrategy(TypingStrategy.BuiltinsOnly)
        .withCommandCacheSize(db.commandCacheSize)
        .withQueryCacheSize(db.queryCacheSize)
        .withParseCacheSize(db.parseCacheSize)

    for {
      writerMetrics <- Resource.eval(PoolMetrics.make[F])
      readerMetrics <- Resource.eval(PoolMetrics.make[F])

      writerPool <- baseBuilder
                      .withHost(db.primaryHost)
                      .withReadTimeout(db.writerReadTimeout)
                      .withConnectionParameters(
                        commonParams ++ Map(
                          "statement_timeout" -> db.writerStatementTimeout.toString,
                          "lock_timeout"      -> db.lockTimeout.toString
                        )
                      )
                      .pooled(db.writerPoolSize)

      readerPool <- baseBuilder
                      .withHost(db.replicaHost)
                      .withReadTimeout(db.readerReadTimeout)
                      .withConnectionParameters(
                        commonParams ++ Map(
                          "statement_timeout"             -> db.readerStatementTimeout.toString,
                          "default_transaction_read_only" -> "on"
                        )
                      )
                      .pooled(db.readerPoolSize)

      writerAttr = Attribute("db.pool.name", "writer")
      readerAttr = Attribute("db.pool.name", "reader")
    } yield DbPools(
      writer = PoolMetrics.instrument(writerPool, writerMetrics, writerAttr),
      reader = PoolMetrics.instrument(readerPool, readerMetrics, readerAttr)
    )
  }

}
