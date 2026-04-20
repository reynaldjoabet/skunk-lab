package kudi.service

import cats.effect.*
import cats.syntax.all.*

import io.circe.Json
import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait AuditService[F[_]] {

  def logAction(
    actorId: Option[Long],
    action: String,
    entityType: String,
    entityId: String,
    ipAddress: Option[String],
    oldValues: Option[Json],
    newValues: Option[Json]
  ): F[Unit]

  def getHistory(entityType: String, entityId: String, limit: Int): F[List[AuditLogEntry]]

}

object AuditService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[AuditService[F]] =
    for {
      m    <- Instrumented.makeMetrics[F]("service.audit")
      repo <- AuditRepo.fromSession(s)
    } yield new AuditService[F] {

      def logAction(
        actorId: Option[Long],
        action: String,
        entityType: String,
        entityId: String,
        ipAddress: Option[String],
        oldValues: Option[Json],
        newValues: Option[Json]
      ): F[Unit] =
        Instrumented.trace("AuditService.logAction", m, Attribute("audit.action", action))(
          repo.log(actorId, action, entityType, entityId, ipAddress, oldValues, newValues)
        )

      def getHistory(entityType: String, entityId: String, limit: Int): F[List[AuditLogEntry]] =
        Instrumented
          .trace("AuditService.getHistory", m, Attribute("audit.entity_type", entityType))(
            repo.findByEntity(entityType, entityId, limit)
          )

    }

}
