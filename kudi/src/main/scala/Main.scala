package kudi

import cats.effect.*
import cats.syntax.all.*
import fs2.io.net.Network

import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.TracerProvider
import pureconfig.ConfigSource

object Main extends IOApp {

  // Reads the `app` block of application.conf; values come from env vars in
  // production. Fails fast with every missing or invalid key listed (e.g.
  // DB_PASSWORD unset) instead of starting with bad config.
  private val loadConfig: IO[AppConfig] =
    IO.blocking(ConfigSource.default.at("app").loadOrThrow[AppConfig])

  def run(args: List[String]): IO[ExitCode] =
    OtelJava
      .autoConfigured[IO]()
      .evalMap { otel =>
        (otel.tracerProvider.tracer("kudi").get, otel.meterProvider.meter("kudi").get).tupled
          .map((otel, _))
      }
      .use { case (otel, (tracer, meter)) =>
        given TracerProvider[IO] = otel.tracerProvider
        given MeterProvider[IO]  = otel.meterProvider
        given Tracer[IO]         = tracer
        given Meter[IO]          = meter
        given Logger[IO]         = Slf4jLogger.getLogger[IO]

        val app = for {
          config <- Resource.eval(loadConfig)
          pools  <- DbPools.make[IO](config)
          _      <- DbMetricsCollector.make[IO](pools.reader)
        } yield pools

        app
          .use { pools =>
            IO.println("Kudi wallet service started. Pools + metrics collector ready.") *>
              IO.never[Unit]
          }
          .as(ExitCode.Success)
      }

}
