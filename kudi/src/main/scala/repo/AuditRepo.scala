package kudi.repo

import cats.effect.*
import cats.syntax.all.*

import io.circe.Json
import kudi.db.Codecs.*
import kudi.domain.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.circe.codec.json.jsonb
import skunk.codec.all.*
import skunk.implicits.*

trait AuditRepo[F[_]] {

  def log(
    actorId: Option[Long],
    action: String,
    entityType: String,
    entityId: String,
    ipAddress: Option[String],
    oldValues: Option[Json],
    newValues: Option[Json]
  ): F[Unit]

  def findByEntity(entityType: String, entityId: String, limit: Int): F[List[AuditLogEntry]]

}

object AuditRepo {

  private val cols =
    "log_id, actor_id, created_at, action, entity_type, entity_id, ip_address::text, user_agent, old_values, new_values, metadata"

  private object SQL {

    val insert: Command[
      Option[Long] *: String *: String *: String *: Option[String] *: Option[Json] *:
        Option[Json] *: EmptyTuple
    ] =
      sql"""
        INSERT INTO audit_log (actor_id, action, entity_type, entity_id, ip_address, old_values, new_values)
        VALUES (${int8.opt}, $text, $text, $text, ${text.opt}::inet, ${jsonb.opt}, ${jsonb.opt})
      """.command

    val findByEntity: Query[String *: String *: Int *: EmptyTuple, AuditLogEntry] =
      sql"""
        SELECT #$cols FROM audit_log
        WHERE entity_type = $text AND entity_id = $text
        ORDER BY created_at DESC
        LIMIT $int4
      """.query(auditLogEntry)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[AuditRepo[F]] =
    for {
      m       <- Instrumented.makeMetrics[F]("repo.audit")
      pInsert <- s.prepare(SQL.insert)
      pFind   <- s.prepare(SQL.findByEntity)
    } yield new AuditRepo[F] {

      def log(
        actorId: Option[Long],
        action: String,
        entityType: String,
        entityId: String,
        ipAddress: Option[String],
        oldValues: Option[Json],
        newValues: Option[Json]
      ): F[Unit] =
        Instrumented.trace(
          "AuditRepo.log",
          m,
          Attribute("audit.action", action),
          Attribute("audit.entity_type", entityType)
        )(
          pInsert
            .execute((actorId, action, entityType, entityId, ipAddress, oldValues, newValues))
            .void
        )

      def findByEntity(entityType: String, entityId: String, limit: Int): F[List[AuditLogEntry]] =
        Instrumented.trace("AuditRepo.findByEntity", m, Attribute("audit.entity_type", entityType))(
          pFind.stream((entityType, entityId, limit), 64).compile.toList
        )

    }

}
