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

trait UsageRepo[F[_]] {

  def record(
    tenantId: UUID,
    subId: UUID,
    metric: String,
    quantity: Long,
    key: String
  ): F[UsageEvent]

  def recordBatch(events: List[UsageEvent]): F[Unit]
  def totalForPeriod(subId: UUID, metric: String, from: OffsetDateTime, to: OffsetDateTime): F[Long]
  def summaryForPeriod(subId: UUID, from: OffsetDateTime, to: OffsetDateTime): F[List[UsageSummary]]
  def recentByTenant(tenantId: UUID, limit: Int): F[List[UsageEvent]]

}

object UsageRepo {

  private val cols =
    "id, tenant_id, subscription_id, metric, quantity, idempotency_key, recorded_at"

  private object SQL {

    val insert: Query[UUID *: UUID *: String *: Long *: String *: EmptyTuple, UsageEvent] =
      sql"""
        INSERT INTO usage_events (tenant_id, subscription_id, metric, quantity, idempotency_key)
        VALUES ($uuid, $uuid, $varchar, $int8, $varchar)
        RETURNING #$cols
      """.query(usageEvent)

    val totalForPeriod
      : Query[UUID *: String *: OffsetDateTime *: OffsetDateTime *: EmptyTuple, Long] =
      sql"""
        SELECT COALESCE(SUM(quantity), 0)
        FROM usage_events
        WHERE subscription_id = $uuid AND metric = $varchar
          AND recorded_at >= $timestamptz AND recorded_at < $timestamptz
      """.query(int8)

    val summaryForPeriod
      : Query[UUID *: OffsetDateTime *: OffsetDateTime *: EmptyTuple, UsageSummary] =
      sql"""
        SELECT metric, COALESCE(SUM(quantity), 0)
        FROM usage_events
        WHERE subscription_id = $uuid
          AND recorded_at >= $timestamptz AND recorded_at < $timestamptz
        GROUP BY metric ORDER BY metric
      """.query(usageSummary)

    val recent: Query[UUID *: Int *: EmptyTuple, UsageEvent] =
      sql"SELECT #$cols FROM usage_events WHERE tenant_id = $uuid ORDER BY recorded_at DESC LIMIT $int4"
        .query(usageEvent)

  }

  def make[F[_]: Concurrent](s: Session[F]): F[UsageRepo[F]] =
    (
      s.prepare(SQL.insert),
      s.prepare(SQL.totalForPeriod),
      s.prepare(SQL.summaryForPeriod),
      s.prepare(SQL.recent)
    ).mapN { (pInsert, pTotal, pSummary, pRecent) =>
      new UsageRepo[F] {

        def record(tenantId: UUID, subId: UUID, metric: String, quantity: Long, key: String) =
          pInsert.unique((tenantId, subId, metric, quantity, key))

        def recordBatch(events: List[UsageEvent]): F[Unit] = {
          val enc = (uuid *: uuid *: varchar *: int8 *: varchar).values.list(events.length)
          s.prepare(
              sql"INSERT INTO usage_events (tenant_id, subscription_id, metric, quantity, idempotency_key) VALUES $enc"
                .command
            )
            .flatMap(
              _.execute(
                events
                  .map(e => (e.tenantId, e.subscriptionId, e.metric, e.quantity, e.idempotencyKey))
              )
            )
            .void
        }

        def totalForPeriod(subId: UUID, metric: String, from: OffsetDateTime, to: OffsetDateTime) =
          pTotal.unique((subId, metric, from, to))

        def summaryForPeriod(subId: UUID, from: OffsetDateTime, to: OffsetDateTime) =
          pSummary.stream((subId, from, to), 64).compile.toList

        def recentByTenant(tenantId: UUID, limit: Int) =
          pRecent.stream((tenantId, limit), 64).compile.toList

      }
    }

}
