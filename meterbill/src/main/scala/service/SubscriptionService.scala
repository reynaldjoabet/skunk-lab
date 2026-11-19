package meterbill.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.domain._
import meterbill.repo._
import skunk.Session

trait SubscriptionService[F[_]] {

  def subscribe(tenantId: UUID, planId: UUID): F[Subscription]
  def cancel(subscriptionId: UUID): F[Unit]
  def getActive(tenantId: UUID): F[Option[Subscription]]
  def listPlans: F[List[Plan]]

}

object SubscriptionService {

  case class TenantNotFound(id: UUID) extends Exception(s"Tenant $id not found")
  case class PlanNotFound(id: UUID)   extends Exception(s"Plan $id not found")

  case class PlanInactive(id: UUID) extends Exception(s"Plan $id is no longer offered")

  case class AlreadySubscribed(tid: UUID)
      extends Exception(s"Tenant $tid already has an active subscription")

  def make[F[_]: Concurrent](s: Session[F]): F[SubscriptionService[F]] =
    (TenantRepo.make(s), PlanRepo.make(s), SubscriptionRepo.make(s)).mapN { (tenants, plans, subs) =>
      new SubscriptionService[F] {

        def subscribe(tenantId: UUID, planId: UUID): F[Subscription] =
          for {
            _        <- tenants.findById(tenantId).flatMap(_.liftTo[F](TenantNotFound(tenantId)))
            plan     <- plans.findById(planId).flatMap(_.liftTo[F](PlanNotFound(planId)))
            _        <- MonadCancelThrow[F].raiseError(PlanInactive(planId)).unlessA(plan.active)
            existing <- subs.findActive(tenantId)
            _        <-
              MonadCancelThrow[F].raiseError(AlreadySubscribed(tenantId)).whenA(existing.isDefined)
            // The check above is only a fast path: two concurrent requests can
            // both pass it. The unique indexes are what actually enforce one
            // active subscription per tenant, so map their violation too.
            created <- subs
                         .create(tenantId, planId)
                         .recoverWith { case skunk.SqlState.UniqueViolation(_) =>
                           MonadCancelThrow[F].raiseError(AlreadySubscribed(tenantId))
                         }
            sub <- created.liftTo[F](PlanNotFound(planId))
          } yield sub

        def cancel(subscriptionId: UUID) = subs.cancel(subscriptionId)
        def getActive(tenantId: UUID)    = subs.findActive(tenantId)
        def listPlans                    = plans.listActive

      }
    }

}
