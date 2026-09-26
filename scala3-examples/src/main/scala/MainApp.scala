import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import cats.effect.*
import fs2.io.net.SocketOption

import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.TracerProvider
import skunk._
import skunk.codec.all._
import skunk.implicits._

def getTelemetry[F[_]: Async: LiftIO]: Resource[F, (TracerProvider[F], MeterProvider[F])] =
  OtelJava
    .autoConfigured[F]()
    .map(otel => (otel.tracerProvider, otel.meterProvider))

// Production: use a connection pool (no-op telemetry here)
def sessionPool: Resource[IO, Resource[IO, Session[IO]]] = {
  implicit val T: TracerProvider[IO] = TracerProvider.noop
  implicit val M: MeterProvider[IO]  = MeterProvider.noop
  Session
    .Builder[IO]
    .withHost("db.example.com")
    .withPort(5432)
    .withUserAndPassword("app_user", "s3cret")
    .withDatabase("mydb")
    .withSSL(SSL.System)                          // TLS with CA-verified certs
    .withRedactionStrategy(RedactionStrategy.All) // redact ALL values in logs/traces
    .withReadTimeout(30.seconds)                  // 30s read timeout
    .pooled(10)                                   // up to 10 concurrent sessions
}

def run(args: List[String]): IO[ExitCode] =
  getTelemetry[IO].use { case (tracerProvider, meterProvider) =>
    implicit val T: TracerProvider[IO] = tracerProvider
    implicit val M: MeterProvider[IO]  = meterProvider
    // now Session.Builder[IO] picks up real tracing + metrics
    IO.pure(ExitCode.Success)
  }

def productionPool[
    F[_]: Temporal: TracerProvider: MeterProvider: fs2.io.net.Network: cats.effect.std.Console
]: Resource[F, Resource[F, Session[F]]] =
  Session
    .Builder[F]
    // Connection
    .withHost("db.prod.internal")
    .withPort(5432)
    .withDatabase("myapp")
    // Auth (dynamic credentials from vault)
    // .withCredentials(fetchCredsFromVault[F])
    // Security
    .withSSL(SSL.System)
    .withRedactionStrategy(RedactionStrategy.All)
    // Types
    .withTypingStrategy(TypingStrategy.SearchPath)
    // Network
    .withSocketOptions(
      List(
        SocketOption.noDelay(true),
        SocketOption.keepAlive(true)
      )
    )
    .withReadTimeout(30.seconds)
    // Postgres params
    .withConnectionParameters(
      Session.DefaultConnectionParameters ++ Map(
        "statement_timeout"                   -> "30000",
        "idle_in_transaction_session_timeout" -> "60000"
      )
    )
    // Caching
    .withCommandCacheSize(2048)
    .withQueryCacheSize(2048)
    .withParseCacheSize(2048)
    // Pool
    .pooled(20)
