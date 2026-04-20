package ledgerpay

import cats.effect._
import cats.syntax.all._

import ledgerpay.db.Database
import ledgerpay.service._
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    OtelJava
      .autoConfigured[IO]()
      .evalMap { otel =>
        (otel.tracerProvider.tracer("ledgerpay").get, otel.meterProvider.meter("ledgerpay").get)
          .tupled
      }
      .use { case (tracer, meter) =>
        implicit val T: Tracer[IO] = tracer
        implicit val M: Meter[IO]  = meter

        Database
          .pool[IO](
            host = "localhost",
            port = 5432,
            user = "ledgerpay",
            password = "s3cret",
            database = "ledgerpay",
            maxConns = 10
          )
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
