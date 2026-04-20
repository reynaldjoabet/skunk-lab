package meterbill

import cats.effect._
import cats.syntax.all._

import com.comcast.ip4s._
import meterbill.config.AppConfig
import meterbill.db.Database
import meterbill.http._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

object Main extends IOApp.Simple {

  implicit val tracer: Tracer[IO] = Tracer.noop
  implicit val meter: Meter[IO]   = Meter.noop

  val run: IO[Unit] = {
    val cfg = AppConfig.load
    Database
      .pool[IO](cfg.db)
      .use { pool =>
        val app = Router(
          "/api" -> (
            TenantRoutes.routes[IO](pool) <+>
              UsageRoutes.routes[IO](pool) <+>
              BillingRoutes.routes[IO](pool) <+>
              HealthRoutes.routes[IO](pool)
          )
        ).orNotFound

        EmberServerBuilder
          .default[IO]
          .withHost(Host.fromString(cfg.http.host).getOrElse(ipv4"0.0.0.0"))
          .withPort(Port.fromInt(cfg.http.port).getOrElse(port"8080"))
          .withHttpApp(app)
          .build
          .useForever
      }
  }

}
