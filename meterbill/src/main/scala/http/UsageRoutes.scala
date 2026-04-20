package meterbill.http

import cats.effect._
import cats.syntax.all._

import io.circe.generic.auto._
import meterbill.domain._
import meterbill.service.MeteringService
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

object UsageRoutes {

  private given CanEqual[Method, Method]     = CanEqual.derived
  private given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  def routes[F[_]: Concurrent](pool: Resource[F, skunk.Session[F]]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}; import dsl._
    HttpRoutes.of[F] {

      case req @ POST -> Root / "tenants" / UUIDVar(id) / "usage" =>
        ErrorHandler.handle(for {
          body  <- req.as[RecordUsageReq]
          event <- pool.use(s => MeteringService.make(s).flatMap(_.record(id, body)))
          resp  <- Created(event)
        } yield resp)

      case GET -> Root / "tenants" / UUIDVar(id) / "usage" / "summary" =>
        ErrorHandler
          .handle(pool.use(s => MeteringService.make(s).flatMap(_.summary(id))).flatMap(Ok(_)))

      case GET -> Root / "tenants" / UUIDVar(id) / "usage" / "recent" =>
        ErrorHandler
          .handle(pool.use(s => MeteringService.make(s).flatMap(_.recent(id, 50))).flatMap(Ok(_)))
    }
  }

}
