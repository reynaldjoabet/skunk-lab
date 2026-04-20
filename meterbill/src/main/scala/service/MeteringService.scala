package meterbill.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.domain._
import meterbill.repo._
import skunk.Session

trait MeteringService[F[_]] {

  def record(tenantId: UUID, req: RecordUsageReq): F[UsageEvent]
  def summary(tenantId: UUID): F[List[UsageSummary]]
  def recent(tenantId: UUID, limit: Int): F[List[UsageEvent]]

}

object MeteringService {

  case class NoActiveSubscription(tenantId: UUID)
      extends Exception(s"Tenant $tenantId has no active subscription")

  case class DuplicateEvent(key: String) extends Exception(s"Idempotency key already used: $key")

  def make[F[_]: Concurrent](s: Session[F]): F[MeteringService[F]] =
    (SubscriptionRepo.make(s), UsageRepo.make(s)).mapN { (subs, usage) =>
      new MeteringService[F] {

        def record(tenantId: UUID, req: RecordUsageReq): F[UsageEvent] =
          subs
            .findActive(tenantId)
            .flatMap {
              case None => MonadCancelThrow[F].raiseError(NoActiveSubscription(tenantId))
              case Some(sub) =>
                usage
                  .record(tenantId, sub.id, req.metric, req.quantity, req.idempotencyKey)
                  .recoverWith { case skunk.SqlState.UniqueViolation(_) =>
                    MonadCancelThrow[F].raiseError(DuplicateEvent(req.idempotencyKey))
                  }
            }

        def summary(tenantId: UUID): F[List[UsageSummary]] =
          subs
            .findActive(tenantId)
            .flatMap {
              case None => List.empty[UsageSummary].pure[F]
              case Some(sub) =>
                usage.summaryForPeriod(sub.id, sub.currentPeriodStart, sub.currentPeriodEnd)
            }

        def recent(tenantId: UUID, limit: Int): F[List[UsageEvent]] =
          usage.recentByTenant(tenantId, limit)

      }
    }

}
