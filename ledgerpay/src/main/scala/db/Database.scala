package ledgerpay.db

import scala.concurrent.duration._

import cats.effect._
import cats.effect.std.Console
import fs2.io.net.Network

import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk._

object Database {

  def pool[F[_]: Temporal: Tracer: Meter: Console: Network](
    host: String,
    port: Int,
    user: String,
    password: String,
    database: String,
    maxConns: Int
  ): Resource[F, Resource[F, Session[F]]] =
    Session
      .Builder[F]
      .withHost(host)
      .withPort(port)
      .withUserAndPassword(user, password)
      .withDatabase(database)
      .withSSL(SSL.System)
      .withRedactionStrategy(RedactionStrategy.All)
      .withTypingStrategy(TypingStrategy.SearchPath) // needed for our enums
      .withReadTimeout(30.seconds)
      .withConnectionParameters(
        Session.DefaultConnectionParameters ++ Map(
          "statement_timeout"                   -> "15000",
          "idle_in_transaction_session_timeout" -> "30000"
        )
      )
      .pooled(maxConns)

}
