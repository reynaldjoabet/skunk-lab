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
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.TracerProvider

object Main extends IOApp.Simple {

  val run: IO[Unit] =
    OtelJava
      .autoConfigured[IO]()
      .evalMap { otel =>
        (
          otel.tracerProvider.tracer("meterbill").get,
          otel.meterProvider.meter("meterbill").get
        ).tupled
          .map((otel, _))
      }
      .use { case (otel, (tracer, meter)) =>
        implicit val TP: TracerProvider[IO] = otel.tracerProvider
        implicit val MP: MeterProvider[IO]  = otel.meterProvider
        implicit val T: Tracer[IO]          = tracer
        implicit val M: Meter[IO]           = meter

        // Inside IO so a bad/missing env var fails with a clear error rather
        // than an ExceptionInInitializerError from Main's constructor.
        IO.delay(AppConfig.load).flatMap { cfg =>
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

  extension [A](res: => Resource[IO, A]) {

    def tracedAcquire(
        name: String
    )(using
        Tracer[IO]
    ): Resource[IO, A] =
      Tracer[IO]
        .span(name + ".acquire")
        .startUnmanaged
        .toResource
        .flatMap { span =>
          Resource.applyFull { poll =>
            Tracer[IO]
              .childScope(span.context)(poll(IO.defer(res.allocatedCase)))
              .guaranteeCase {
                case Outcome.Succeeded(_) => span.end
                case Outcome.Errored(e)   => span.recordException(e) *> span.end
                case Outcome.Canceled()   => span.end
              }
          }
        }

  }

}
