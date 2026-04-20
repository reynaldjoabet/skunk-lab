import cats.effect.kernel.Resource
import cats.effect.IO

import org.typelevel.otel4s.metrics.Meter.Implicits.{noop => noopMeter}
import org.typelevel.otel4s.trace.Tracer.Implicits.{noop => noopTracer}
import skunk.Session

object ConnectionPoolSkunk {

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
