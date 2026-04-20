package idmgmt

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
      user = "idmgmt",
      password = "s3cret",
      database = "idmgmt",
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
      applicationName = "idmgmt",
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
        (otel.tracerProvider.tracer("idmgmt").get, otel.meterProvider.meter("idmgmt").get).tupled
      }
      .use { case (tracer, meter) =>
        given Tracer[IO] = tracer
        given Meter[IO]  = meter

        DbPools
          .make[IO](config)
          .use { pools =>
            IO.println("Identity Management service started. Pools ready.") *>
              IO.never[Unit]
          }
          .as(ExitCode.Success)
      }

}
