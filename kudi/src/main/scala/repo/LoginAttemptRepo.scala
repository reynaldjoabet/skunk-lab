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
import skunk.codec.all.*
import skunk.implicits.*

trait LoginAttemptRepo[F[_]] {

  def record(
    userId: Option[Long],
    success: Boolean,
    identifier: String,
    ipAddress: String,
    userAgent: Option[String],
    failureReason: Option[String]
  ): F[Unit]

  def findRecent(identifier: String, limit: Int): F[List[LoginAttempt]]
  def countRecentFailures(ipAddress: String, withinMinutes: Int): F[Long]

}

object LoginAttemptRepo {

  private val cols =
    "attempt_id, user_id, created_at, success, identifier, ip_address::text, user_agent, failure_reason"

  private object SQL {

    val insert: Command[
      Option[Long] *: Boolean *: String *: String *: Option[String] *: Option[String] *: EmptyTuple
    ] =
      sql"""
        INSERT INTO auth.login_attempts (user_id, success, identifier, ip_address, user_agent, failure_reason)
        VALUES (${int8.opt}, $bool, $text, $text::inet, ${text.opt}, ${text.opt})
      """.command

    val findRecent: Query[String *: Int *: EmptyTuple, LoginAttempt] =
      sql"""
        SELECT #$cols FROM auth.login_attempts
        WHERE identifier = $text
        ORDER BY created_at DESC
        LIMIT $int4
      """.query(loginAttempt)

    val countRecentFailures: Query[String *: Int *: EmptyTuple, Long] =
      sql"""
        SELECT count(*) FROM auth.login_attempts
        WHERE ip_address = $text::inet AND NOT success
          AND created_at > now() - ($int4 || ' minutes')::interval
      """.query(int8)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[LoginAttemptRepo[F]] =
    for {
      m       <- Instrumented.makeMetrics[F]("repo.login_attempt")
      pInsert <- s.prepare(SQL.insert)
      pRecent <- s.prepare(SQL.findRecent)
      pCount  <- s.prepare(SQL.countRecentFailures)
    } yield new LoginAttemptRepo[F] {

      def record(
        userId: Option[Long],
        success: Boolean,
        identifier: String,
        ipAddress: String,
        userAgent: Option[String],
        failureReason: Option[String]
      ): F[Unit] =
        Instrumented.trace("LoginAttemptRepo.record", m, Attribute("login.success", success))(
          pInsert.execute((userId, success, identifier, ipAddress, userAgent, failureReason)).void
        )

      def findRecent(identifier: String, limit: Int): F[List[LoginAttempt]] =
        Instrumented.trace("LoginAttemptRepo.findRecent", m)(
          pRecent.stream((identifier, limit), 64).compile.toList
        )

      def countRecentFailures(ipAddress: String, withinMinutes: Int): F[Long] =
        Instrumented.trace("LoginAttemptRepo.countRecentFailures", m)(
          pCount.unique((ipAddress, withinMinutes))
        )

    }

}
