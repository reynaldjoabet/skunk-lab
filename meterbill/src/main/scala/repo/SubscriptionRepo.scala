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

  def create(tenantId: UUID, planId: UUID): F[Subscription]
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

    val insert: Query[UUID *: UUID *: EmptyTuple, Subscription] =
      sql"""
        INSERT INTO subscriptions (tenant_id, plan_id)
        VALUES ($uuid, $uuid)
        RETURNING #$cols
      """.query(subscription)

    val byId: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE id = $uuid".query(subscription)

    val byTenant: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE tenant_id = $uuid ORDER BY created_at DESC"
        .query(subscription)

    val active: Query[UUID, Subscription] =
      sql"SELECT #$cols FROM subscriptions WHERE tenant_id = $uuid AND status = 'active' LIMIT 1"
        .query(subscription)

    val doCancel: Command[UUID] =
      sql"UPDATE subscriptions SET status = 'canceled', canceled_at = now() WHERE id = $uuid"
        .command

    val doAdvance: Command[OffsetDateTime *: OffsetDateTime *: UUID *: EmptyTuple] =
      sql"UPDATE subscriptions SET current_period_start = $timestamptz, current_period_end = $timestamptz WHERE id = $uuid"
        .command

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

        def create(tenantId: UUID, planId: UUID) = pInsert.unique((tenantId, planId))
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
