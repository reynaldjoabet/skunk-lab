package meterbill.http

import cats.effect._
import cats.syntax.all._

import io.circe.generic.auto._
import meterbill.domain._
import meterbill.service.BillingService
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl

object BillingRoutes {

  private given CanEqual[Method, Method]     = CanEqual.derived
  private given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  def routes[F[_]: Concurrent](pool: Resource[F, skunk.Session[F]]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}; import dsl._
    HttpRoutes.of[F] {

      case GET -> Root / "subscriptions" / UUIDVar(subId) / "preview" =>
        ErrorHandler
          .handle(pool.use(s => BillingService.make(s).flatMap(_.preview(subId))).flatMap(Ok(_)))

      case POST -> Root / "subscriptions" / UUIDVar(subId) / "invoice" =>
        ErrorHandler.handle(for {
          inv  <- pool.use(s => BillingService.make(s).flatMap(_.generate(subId)))
          resp <- Created(inv)
        } yield resp)

      case GET -> Root / "tenants" / UUIDVar(id) / "invoices" =>
        ErrorHandler
          .handle(pool.use(s => BillingService.make(s).flatMap(_.listInvoices(id))).flatMap(Ok(_)))

      case GET -> Root / "invoices" / UUIDVar(id) =>
        ErrorHandler.handle(
          pool
            .use(s => BillingService.make(s).flatMap(_.getInvoiceWithLines(id)))
            .flatMap {
              case Some((inv, lines)) => Ok((inv, lines))
              case None               => NotFound(ErrorResp(s"Invoice $id not found"))
            }
        )

      case POST -> Root / "invoices" / UUIDVar(id) / "pay" =>
        ErrorHandler.handle(
          pool
            .use(s => BillingService.make(s).flatMap(_.markPaid(id))) *> Ok(Map("status" -> "paid"))
        )
    }
  }

}
