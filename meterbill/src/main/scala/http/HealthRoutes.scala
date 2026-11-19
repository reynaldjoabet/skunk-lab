package meterbill.http

import scala.concurrent.duration._

import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._

import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import skunk._
import skunk.codec.all._
import skunk.implicits._

object HealthRoutes {

  private given CanEqual[Method, Method]     = CanEqual.derived
  private given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  case class HealthResp(status: String, db: String)

  private val ProbeTimeout = 3.seconds

  def routes[F[_]: Temporal](pool: Resource[F, Session[F]]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}; import dsl._
    HttpRoutes.of[F] { case GET -> Root / "health" =>
      pool
        .use(_.unique(sql"SELECT 1".query(int4)))
        // A probe must answer quickly even when the pool is exhausted or the
        // database hangs; report degraded instead of waiting it out.
        .timeout(ProbeTimeout)
        .attempt
        .flatMap {
          case Right(_) => Ok(HealthResp("ok", "connected"))
          case Left(_)  => ServiceUnavailable(HealthResp("degraded", "disconnected"))
        }
    }
  }

}
