package ledgerpay

import cats.effect._
import cats.syntax.all._

import ledgerpay.db.Database
import ledgerpay.service._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.TracerProvider
import pureconfig.ConfigSource

object Main extends IOApp {

  // Fails fast with every missing or invalid key listed (e.g. DB_PASSWORD unset).
  private val loadDbConfig: IO[db.DbConfig] =
    IO.blocking(ConfigSource.default.at("ledgerpay.db").loadOrThrow[db.DbConfig])

  def run(args: List[String]): IO[ExitCode] =
    OtelJava
      .autoConfigured[IO]()
      .evalMap { otel =>
        (
          otel.tracerProvider.tracer("ledgerpay").get,
          otel.meterProvider.meter("ledgerpay").get
        ).tupled.map((otel, _))
      }
      .use { case (otel, (tracer, meter)) =>
        implicit val TP: TracerProvider[IO] = otel.tracerProvider
        implicit val MP: MeterProvider[IO]  = otel.meterProvider
        implicit val T: Tracer[IO]          = tracer
        implicit val M: Meter[IO]           = meter

        Resource
          .eval(loadDbConfig)
          .flatMap { cfg =>
            Database.pool[IO](
              host = cfg.host,
              port = cfg.port,
              user = cfg.user,
              password = cfg.password,
              database = cfg.database,
              maxConns = cfg.maxConnections
            )
          }
          .use { pool =>
            // Construct services per-request using the pool
            // In a real app, wire these into http4s routes:
            //
            //   pool.use { session =>
            //     for {
            //       transferSvc <- TransferService.fromSession(session)
            //       accountSvc  <- AccountService.fromSession(session)
            //       ...
            //     } yield ()
            //   }

            IO.println("LedgerPay started. Pool ready.") *>
              IO.never[Unit]
          }
          .as(ExitCode.Success)
      }

}
