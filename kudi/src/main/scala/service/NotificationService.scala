package kudi.service

import cats.effect.*
import cats.syntax.all.*

import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait NotificationService[F[_]] {

  def getPreferences(userId: Long): F[List[NotificationPreference]]

  def updatePreference(
    userId: Long,
    channelId: NotificationChannelId,
    eventType: String,
    isEnabled: Boolean
  ): F[Unit]

  def getTemplate(code: String): F[Option[NotificationTemplate]]

}

object NotificationService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[NotificationService[F]] =
    for {
      m     <- Instrumented.makeMetrics[F]("service.notification")
      repo  <- NotificationRepo.fromSession(s)
      audit <- AuditRepo.fromSession(s)
    } yield new NotificationService[F] {

      def getPreferences(userId: Long): F[List[NotificationPreference]] =
        Instrumented.trace("NotificationService.getPreferences", m, Attribute("user.id", userId))(
          repo.getPreferences(userId)
        )

      def updatePreference(
        userId: Long,
        channelId: NotificationChannelId,
        eventType: String,
        isEnabled: Boolean
      ): F[Unit] =
        Instrumented.trace("NotificationService.updatePreference", m, Attribute("user.id", userId))(
          for {
            _ <- repo.upsertPreference(userId, channelId, eventType, isEnabled)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "notification_preference_updated",
                   entityType = "notification_preference",
                   entityId = s"$userId:${channelId.value}:$eventType",
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def getTemplate(code: String): F[Option[NotificationTemplate]] =
        Instrumented.trace("NotificationService.getTemplate", m, Attribute("template.code", code))(
          repo.findTemplateByCode(code)
        )

    }

}
