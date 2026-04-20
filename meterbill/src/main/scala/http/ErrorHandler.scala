package meterbill.http

import cats.effect._
import cats.syntax.all._

import io.circe.generic.auto._
import meterbill.service._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._

case class ErrorResp(error: String)

object ErrorHandler {

  def handle[F[_]: Concurrent](f: F[Response[F]]): F[Response[F]] =
    f.handleErrorWith {
      case e: MeteringService.NoActiveSubscription =>
        Response[F](Status.UnprocessableEntity).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: MeteringService.DuplicateEvent =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.SubNotFound =>
        Response[F](Status.NotFound).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.PlanNotFound =>
        Response[F](Status.NotFound).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.InvoiceNotFound =>
        Response[F](Status.NotFound).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.AlreadyInvoiced =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: SubscriptionService.AlreadySubscribed =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: SubscriptionService.TenantNotFound =>
        Response[F](Status.NotFound).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: SubscriptionService.PlanNotFound =>
        Response[F](Status.NotFound).withEntity(ErrorResp(e.getMessage)).pure[F]
      case _ =>
        Response[F](Status.InternalServerError).withEntity(ErrorResp("Internal error")).pure[F]
    }

}
