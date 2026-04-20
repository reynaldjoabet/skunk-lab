package ledgerpay.repo

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect._
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.all._
import fs2.Stream

import io.circe.Json
import ledgerpay.db.Codecs._
import skunk._
import skunk.circe.codec.json.jsonb
import skunk.codec.all._
import skunk.implicits._

case class AuditEntry(
  id: Long,
  entityType: String,
  entityId: UUID,
  action: String,
  actorId: Option[UUID],
  payload: Option[Json],
  createdAt: OffsetDateTime
)

trait AuditRepo[F[_]] {

  def log(
    entityType: String,
    entityId: UUID,
    action: String,
    actorId: Option[UUID],
    payload: Option[Json]
  ): F[Unit]

  def findByEntity(entityType: String, entityId: UUID): Stream[F, AuditEntry]

}

object AuditRepo {

  private val auditEntry: Decoder[AuditEntry] =
    (int8 *: varchar *: uuid *: varchar *: uuid.opt *: jsonb.opt *: timestamptz).to[AuditEntry]

  private object SQL {

    val insert: Command[String *: UUID *: String *: Option[UUID] *: Option[Json] *: EmptyTuple] =
      sql"""
        INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload)
        VALUES ($varchar, $uuid, $varchar, ${uuid.opt}, ${jsonb.opt})
      """.command

    val findByEntity: Query[String *: UUID *: EmptyTuple, AuditEntry] =
      sql"""
        SELECT id, entity_type, entity_id, action, actor_id, payload, created_at
        FROM audit_log
        WHERE entity_type = $varchar AND entity_id = $uuid
        ORDER BY created_at DESC
      """.query(auditEntry)

  }

  def fromSession[F[_]: MonadCancelThrow](s: Session[F]): F[AuditRepo[F]] =
    (s.prepare(SQL.insert), s.prepare(SQL.findByEntity)).mapN { (pInsert, pFind) =>
      new AuditRepo[F] {

        def log(
          entityType: String,
          entityId: UUID,
          action: String,
          actorId: Option[UUID],
          payload: Option[Json]
        ): F[Unit] =
          pInsert.execute((entityType, entityId, action, actorId, payload)).void

        def findByEntity(entityType: String, entityId: UUID): Stream[F, AuditEntry] =
          pFind.stream((entityType, entityId), 64)

      }
    }

}
