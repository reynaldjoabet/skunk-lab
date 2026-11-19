package meterbill.http

import cats.effect._
import cats.syntax.all._

import io.circe.generic.auto._
import meterbill.domain.Validation
import meterbill.repo.TenantRepo
import meterbill.service._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.typelevel.log4cats.slf4j.Slf4jLogger

case class ErrorResp(error: String)

object ErrorHandler {

  def handle[F[_]: Sync](f: F[Response[F]]): F[Response[F]] =
    f.handleErrorWith {
      // Malformed, undecodable or over-limit request body: a client error
      // (400/422), not a 500.
      case e: MessageFailure     => e.toHttpResponse[F](HttpVersion.`HTTP/1.1`).pure[F]
      case e: Validation.Invalid =>
        Response[F](Status.UnprocessableContent).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: TenantRepo.TenantExists =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.PeriodNotEnded =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: BillingService.InvoiceNotPayable =>
        Response[F](Status.Conflict).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: SubscriptionService.PlanInactive =>
        Response[F](Status.UnprocessableContent).withEntity(ErrorResp(e.getMessage)).pure[F]
      case e: MeteringService.NoActiveSubscription =>
        Response[F](Status.UnprocessableContent).withEntity(ErrorResp(e.getMessage)).pure[F]
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
      case e =>
        // Unexpected: keep the client response generic, but log the cause.
        Slf4jLogger.getLogger[F].error(e)("Unhandled error in request") *>
          Response[F](Status.InternalServerError).withEntity(ErrorResp("Internal error")).pure[F]
    }

}
