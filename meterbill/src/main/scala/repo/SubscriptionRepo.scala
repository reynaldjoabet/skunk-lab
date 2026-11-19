package meterbill.repo

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.db.Codecs._
import meterbill.domain._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait SubscriptionRepo[F[_]] {

  /**
    * Creates a subscription whose first period length follows the plan's billing period.
    */
  def create(tenantId: UUID, planId: UUID): F[Option[Subscription]]
  def findById(id: UUID): F[Option[Subscription]]
  def findByTenant(tenantId: UUID): F[List[Subscription]]
  def findActive(tenantId: UUID): F[Option[Subscription]]
  def cancel(id: UUID): F[Unit]
  def advancePeriod(id: UUID, newStart: OffsetDateTime, newEnd: OffsetDateTime): F[Unit]

}

object SubscriptionRepo {

  private val cols =
    "id, tenant_id, plan_id, status, starts_at, current_period_start, current_period_end, canceled_at, created_at"

  private object SQL {

    // Selecting from plans sets the first period from the plan's billing
    // period (the column default is always one month) and returns no row if
    // the plan does not exist.
    val insert: Query[UUID *: UUID *: EmptyTuple, Subscription] =
      sql"""
        INSERT INTO subscriptions (tenant_id, plan_id, current_period_end)
        SELECT $uuid, p.id,
               date_trunc('month', now()) +
                 CASE p.period WHEN 'yearly' THEN interval '1 year' ELSE interval '1 month' END
        FROM plans p
        WHERE p.id = $uuid
        RETURNING #$cols
      """.query(subscription)

    val byId: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE id = $uuid".query(subscription)

    val byTenant: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE tenant_id = $uuid ORDER BY created_at DESC".query(
        subscription
      )

    val active: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE tenant_id = $uuid AND status = 'active' LIMIT 1"
        .query(subscription)

    val doCancel: Command[UUID] =
      sql"UPDATE subscriptions SET status = 'canceled', canceled_at = now() WHERE id = $uuid".command

    val doAdvance: Command[OffsetDateTime *: OffsetDateTime *: UUID *: EmptyTuple] =
      sql"UPDATE subscriptions SET current_period_start = $timestamptz, current_period_end = $timestamptz WHERE id = $uuid".command

  }

  def make[F[_]: Concurrent](s: Session[F]): F[SubscriptionRepo[F]] =
    (
      s.prepare(SQL.insert),
      s.prepare(SQL.byId),
      s.prepare(SQL.byTenant),
      s.prepare(SQL.active),
      s.prepare(SQL.doCancel),
      s.prepare(SQL.doAdvance)
    ).mapN { (pInsert, pById, pByTenant, pActive, pCancel, pAdvance) =>
      new SubscriptionRepo[F] {

        def create(tenantId: UUID, planId: UUID) = pInsert.option((tenantId, planId))
        def findById(id: UUID)                   = pById.option(id)
        def findByTenant(tenantId: UUID)         = pByTenant.stream(tenantId, 32).compile.toList
        def findActive(tenantId: UUID)           = pActive.option(tenantId)
        def cancel(id: UUID)                     = pCancel.execute(id).void

        def advancePeriod(id: UUID, s: OffsetDateTime, e: OffsetDateTime) = pAdvance
          .execute((s, e, id))
          .void

      }
    }

}
