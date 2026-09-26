import cats.effect.kernel.Resource
import cats.effect.IO

import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider
import skunk.Session

object ConnectionPoolSkunk {

  implicit val tracerProvider: TracerProvider[IO] = TracerProvider.noop
  implicit val meterProvider: MeterProvider[IO]   = MeterProvider.noop

  val kunkConnectionPool: Resource[IO, Resource[IO, Session[IO]]] = Session.pooled[IO](
    host = "localhost",
    port = 5432,
    user = "jimmy",
    database = "world",
    password = Some("banana"),
    max = 10,
    debug = false
  )

}
