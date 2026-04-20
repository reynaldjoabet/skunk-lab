package kudi

import scala.concurrent.duration.*

import cats.effect.*
import cats.syntax.all.*
import fs2.io.net.Network

import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

object Main extends IOApp {

  private val config = AppConfig(
    db = DbConfig(
      primaryHost = "localhost",
      replicaHost = "localhost",
      port = 5432,
      user = "kudi",
      password = "s3cret",
      database = "kudi",
      writerPoolSize = 25,
      readerPoolSize = 50,
      writerReadTimeout = 5.seconds,
      readerReadTimeout = 3.seconds,
      writerStatementTimeout = 5000,
      readerStatementTimeout = 3000,
      idleInTransactionTimeout = 10000,
      lockTimeout = 2000,
      tcpKeepalivesIdle = 60,
      tcpKeepalivesInterval = 10,
      tcpKeepalivesCount = 3,
      ssl = "none",
      applicationName = "kudi",
      workMem = "8MB",
      commandCacheSize = 512,
      queryCacheSize = 512,
      parseCacheSize = 512
    )
  )

  def run(args: List[String]): IO[ExitCode] =
    OtelJava
      .autoConfigured[IO]()
      .evalMap { otel =>
        (otel.tracerProvider.tracer("kudi").get, otel.meterProvider.meter("kudi").get).tupled
      }
      .use { case (tracer, meter) =>
        given Tracer[IO] = tracer
        given Meter[IO]  = meter

        val app = for {
          pools <- DbPools.make[IO](config)
          _     <- DbMetricsCollector.make[IO](pools.reader)
        } yield pools

        app
          .use { pools =>
            IO.println("Kudi wallet service started. Pools + metrics collector ready.") *>
              IO.never[Unit]
          }
          .as(ExitCode.Success)
      }

}
