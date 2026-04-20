import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import cats.effect.*
import cats.implicits.catsSyntaxTuple2Semigroupal
import fs2.io.net.SocketOption

import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk._
import skunk.codec.all._
import skunk.implicits._

def getTelemetry[F[_]: Async: LiftIO]: Resource[F, (Tracer[F], Meter[F])] =
  OtelJava
    .autoConfigured[F]()
    .evalMap { otel =>
      (otel.tracerProvider.tracer("my-app").get, otel.meterProvider.meter("my-app").get).tupled
    }

// Production: use a connection pool
def sessionPool: Resource[IO, Resource[IO, Session[IO]]] =
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

def run(args: List[String]): IO[ExitCode] =
  getTelemetry[IO].use { case (tracer, meter) =>
    implicit val T = tracer
    implicit val M = meter
    // now Session.Builder[IO] picks up real tracing + metrics
    IO.pure(ExitCode.Success)
  }

def productionPool[F[_]: Temporal: Tracer: Meter: fs2.io.net.Network: cats.effect.std.Console]
  : Resource[F, Resource[F, Session[F]]] =
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
