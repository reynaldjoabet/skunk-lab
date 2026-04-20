package meterbill.db

import scala.concurrent.duration._

import cats.effect._
import cats.effect.std.Console
import fs2.io.net.Network

import meterbill.config.DbConfig
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk._

object Database {

  def pool[F[_]: Temporal: Tracer: Meter: Console: Network](
    cfg: DbConfig
  ): Resource[F, Resource[F, Session[F]]] =
    Session
      .Builder[F]
      .withHost(cfg.host)
      .withPort(cfg.port)
      .withUserAndPassword(cfg.user, cfg.password)
      .withDatabase(cfg.database)
      .withTypingStrategy(TypingStrategy.SearchPath)
      .withRedactionStrategy(RedactionStrategy.All)
      .withReadTimeout(30.seconds)
      .withConnectionParameters(
        Session.DefaultConnectionParameters ++ Map(
          "statement_timeout" -> "30000"
        )
      )
      .pooled(cfg.poolSize)

}
