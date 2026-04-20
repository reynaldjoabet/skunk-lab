package meterbill.repo

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.db.Codecs._
import meterbill.domain._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait PlanRepo[F[_]] {

  def findById(id: UUID): F[Option[Plan]]
  def listActive: F[List[Plan]]

}

object PlanRepo {

  private object SQL {

    val byId: Query[UUID, Plan] =
      sql"SELECT id, name, tier, period, base_price_cents, included_units, overage_rate_cents, active, created_at FROM plans WHERE id = $uuid"
        .query(plan)

    val active: Query[Void, Plan] =
      sql"SELECT id, name, tier, period, base_price_cents, included_units, overage_rate_cents, active, created_at FROM plans WHERE active = true ORDER BY base_price_cents"
        .query(plan)

  }

  def make[F[_]: MonadCancelThrow](s: Session[F]): F[PlanRepo[F]] =
    s.prepare(SQL.byId)
      .map { pById =>
        new PlanRepo[F] {

          def findById(id: UUID) = pById.option(id)
          def listActive         = s.execute(SQL.active)

        }
      }

}
