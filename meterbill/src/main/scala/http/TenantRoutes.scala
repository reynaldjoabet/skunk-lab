package meterbill.http

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import io.circe.generic.auto._
import meterbill.domain._
import meterbill.repo.TenantRepo
import meterbill.service.SubscriptionService
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

object TenantRoutes {

  private given CanEqual[Method, Method]     = CanEqual.derived
  private given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  def routes[F[_]: Concurrent](pool: Resource[F, skunk.Session[F]]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}; import dsl._
    HttpRoutes.of[F] {

      case req @ POST -> Root / "tenants" =>
        ErrorHandler.handle(for {
          body <- req.as[CreateTenantReq]
          tenant <-
            pool.use(s => TenantRepo.make(s).flatMap(_.create(body.slug, body.name, body.email)))
          resp <- Created(tenant)
        } yield resp)

      case GET -> Root / "tenants" / UUIDVar(id) =>
        ErrorHandler.handle(
          pool
            .use(s => TenantRepo.make(s).flatMap(_.findById(id)))
            .flatMap {
              case Some(t) => Ok(t)
              case None    => NotFound(ErrorResp(s"Tenant $id not found"))
            }
        )

      case req @ POST -> Root / "tenants" / UUIDVar(id) / "subscribe" =>
        ErrorHandler.handle(for {
          body <- req.as[SubscribeReq]
          sub  <- pool.use(s => SubscriptionService.make(s).flatMap(_.subscribe(id, body.planId)))
          resp <- Created(sub)
        } yield resp)

      case GET -> Root / "tenants" / UUIDVar(id) / "subscription" =>
        ErrorHandler.handle(
          pool
            .use(s => SubscriptionService.make(s).flatMap(_.getActive(id)))
            .flatMap {
              case Some(sub) => Ok(sub)
              case None      => NotFound(ErrorResp("No active subscription"))
            }
        )

      case GET -> Root / "plans" =>
        pool.use(s => SubscriptionService.make(s).flatMap(_.listPlans)).flatMap(Ok(_))
    }
  }

}
