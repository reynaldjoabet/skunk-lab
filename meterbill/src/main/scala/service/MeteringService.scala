package meterbill.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.domain._
import meterbill.repo._
import skunk.Session

trait MeteringService[F[_]] {

  /**
    * Records a usage event. Retrying with the same idempotency key and payload returns the
    * originally stored event (`created = false`) instead of counting the usage twice.
    */
  def record(tenantId: UUID, req: RecordUsageReq): F[MeteringService.Recorded]
  def summary(tenantId: UUID): F[List[UsageSummary]]
  def recent(tenantId: UUID, limit: Int): F[List[UsageEvent]]

}

object MeteringService {

  case class NoActiveSubscription(tenantId: UUID)
      extends Exception(s"Tenant $tenantId has no active subscription")

  case class DuplicateEvent(key: String)
      extends Exception(s"Idempotency key already used for a different event: $key")

  case class Recorded(event: UsageEvent, created: Boolean)

  def make[F[_]: Concurrent](s: Session[F]): F[MeteringService[F]] =
    (SubscriptionRepo.make(s), UsageRepo.make(s)).mapN { (subs, usage) =>
      new MeteringService[F] {

        def record(tenantId: UUID, req: RecordUsageReq): F[Recorded] =
          subs
            .findActive(tenantId)
            .flatMap {
              case None      => MonadCancelThrow[F].raiseError(NoActiveSubscription(tenantId))
              case Some(sub) =>
                // A retry with the same payload returns the stored event; the
                // same key with a different payload is a conflict.
                usage
                  .record(tenantId, sub.id, req.metric, req.quantity, req.idempotencyKey)
                  .flatMap {
                    case Some((event, created)) => Recorded(event, created).pure[F]
                    case None                   =>
                      MonadCancelThrow[F].raiseError(DuplicateEvent(req.idempotencyKey))
                  }
            }

        def summary(tenantId: UUID): F[List[UsageSummary]] =
          subs
            .findActive(tenantId)
            .flatMap {
              case None      => List.empty[UsageSummary].pure[F]
              case Some(sub) =>
                usage.summaryForPeriod(sub.id, sub.currentPeriodStart, sub.currentPeriodEnd)
            }

        def recent(tenantId: UUID, limit: Int): F[List[UsageEvent]] =
          usage.recentByTenant(tenantId, limit)

      }
    }

}
