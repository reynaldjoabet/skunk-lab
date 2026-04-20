package kudi.repo

import cats.effect.*
import cats.syntax.all.*

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

trait NotificationRepo[F[_]] {

  def findTemplateByCode(code: String): F[Option[NotificationTemplate]]
  def getPreferences(userId: Long): F[List[NotificationPreference]]

  def upsertPreference(
    userId: Long,
    channelId: NotificationChannelId,
    eventType: String,
    isEnabled: Boolean
  ): F[Unit]

}

object NotificationRepo {

  private val templateCols =
    "template_id, created_at, channel_id, is_active, code, subject_template, body_template, metadata"

  private val prefCols =
    "user_id, channel_id, is_enabled, event_type"

  private object SQL {

    val findTemplateByCode: Query[String, NotificationTemplate] =
      sql"""
        SELECT #$templateCols FROM notify.templates
        WHERE code = $text AND is_active = true
      """.query(notificationTemplate)

    val getPreferences: Query[Long, NotificationPreference] =
      sql"""
        SELECT #$prefCols FROM notify.preferences
        WHERE user_id = $int8
        ORDER BY channel_id, event_type
      """.query(notificationPreference)

    val upsertPreference: Command[Long *: Short *: String *: Boolean *: Boolean *: EmptyTuple] =
      sql"""
        INSERT INTO notify.preferences (user_id, channel_id, event_type, is_enabled)
        VALUES ($int8, $int2, $text, $bool)
        ON CONFLICT (user_id, channel_id, event_type) DO UPDATE SET is_enabled = $bool
      """.command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[NotificationRepo[F]] =
    for {
      m             <- Instrumented.makeMetrics[F]("repo.notification")
      pFindTemplate <- s.prepare(SQL.findTemplateByCode)
      pGetPrefs     <- s.prepare(SQL.getPreferences)
      pUpsertPref   <- s.prepare(SQL.upsertPreference)
    } yield new NotificationRepo[F] {

      def findTemplateByCode(code: String): F[Option[NotificationTemplate]] =
        Instrumented
          .trace("NotificationRepo.findTemplateByCode", m, Attribute("template.code", code))(
            pFindTemplate.option(code)
          )

      def getPreferences(userId: Long): F[List[NotificationPreference]] =
        Instrumented.trace("NotificationRepo.getPreferences", m, Attribute("user.id", userId))(
          pGetPrefs.stream(userId, 64).compile.toList
        )

      def upsertPreference(
        userId: Long,
        channelId: NotificationChannelId,
        eventType: String,
        isEnabled: Boolean
      ): F[Unit] =
        Instrumented.trace("NotificationRepo.upsertPreference", m, Attribute("user.id", userId))(
          pUpsertPref.execute((userId, channelId.value, eventType, isEnabled, isEnabled)).void
        )

    }

}
