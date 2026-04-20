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

trait SessionRepo[F[_]] {

  def createSession(
    userId: Long,
    tokenHash: Array[Byte],
    ipAddress: String,
    userAgent: Option[String],
    deviceFingerprint: Option[String],
    ttlHours: Int
  ): F[Long]

  def revokeSession(sessionId: Long, reasonId: SessionRevokeReasonId): F[Unit]
  def revokeAllSessions(userId: Long, reasonId: SessionRevokeReasonId): F[Int]
  def findActiveSessions(userId: Long): F[List[AuthSession]]
  def touchSession(sessionId: Long): F[Unit]
  def findById(sessionId: Long): F[Option[AuthSession]]

}

object SessionRepo {

  private val cols =
    "session_id, user_id, created_at, expires_at, last_active_at, revoked_at, revoke_reason_id, is_active, mfa_verified, device_fingerprint, ip_address::text, user_agent, metadata"

  private object SQL {

    val createSession: Query[
      Long *: Array[Byte] *: String *: Option[String] *: Option[String] *: Int *: EmptyTuple,
      Long
    ] =
      sql"""SELECT auth.create_session($int8, $bytea, $text::inet, ${text.opt}, ${text
          .opt}, $int4)""".query(int8)

    val revokeSession: Command[Short *: Long *: EmptyTuple] =
      sql"""
        UPDATE auth.sessions
        SET is_active = false, revoked_at = now(), revoke_reason_id = $int2
        WHERE session_id = $int8 AND is_active = true
      """.command

    val revokeAllSessions: Query[Long *: Short *: EmptyTuple, Long] =
      sql"""SELECT auth.revoke_all_sessions($int8, $int2)""".query(int8)

    val findActiveSessions: Query[Long, AuthSession] =
      sql"""
        SELECT #$cols FROM auth.sessions
        WHERE user_id = $int8 AND is_active = true
        ORDER BY last_active_at DESC
      """.query(authSession)

    val touchSession: Command[Long] =
      sql"""UPDATE auth.sessions SET last_active_at = now() WHERE session_id = $int8""".command

    val findById: Query[Long, AuthSession] =
      sql"""SELECT #$cols FROM auth.sessions WHERE session_id = $int8""".query(authSession)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[SessionRepo[F]] =
    for {
      m           <- Instrumented.makeMetrics[F]("repo.session")
      pCreate     <- s.prepare(SQL.createSession)
      pRevoke     <- s.prepare(SQL.revokeSession)
      pRevokeAll  <- s.prepare(SQL.revokeAllSessions)
      pFindActive <- s.prepare(SQL.findActiveSessions)
      pTouch      <- s.prepare(SQL.touchSession)
      pFindById   <- s.prepare(SQL.findById)
    } yield new SessionRepo[F] {

      def createSession(
        userId: Long,
        tokenHash: Array[Byte],
        ipAddress: String,
        userAgent: Option[String],
        deviceFingerprint: Option[String],
        ttlHours: Int
      ): F[Long] =
        Instrumented.trace("SessionRepo.createSession", m, Attribute("user.id", userId))(
          pCreate.unique((userId, tokenHash, ipAddress, userAgent, deviceFingerprint, ttlHours))
        )

      def revokeSession(sessionId: Long, reasonId: SessionRevokeReasonId): F[Unit] =
        Instrumented.trace("SessionRepo.revokeSession", m, Attribute("session.id", sessionId))(
          pRevoke.execute((reasonId.value, sessionId)).void
        )

      def revokeAllSessions(userId: Long, reasonId: SessionRevokeReasonId): F[Int] =
        Instrumented.trace("SessionRepo.revokeAllSessions", m, Attribute("user.id", userId))(
          pRevokeAll.unique((userId, reasonId.value)).map(_.toInt)
        )

      def findActiveSessions(userId: Long): F[List[AuthSession]] =
        Instrumented.trace("SessionRepo.findActiveSessions", m, Attribute("user.id", userId))(
          pFindActive.stream(userId, 64).compile.toList
        )

      def touchSession(sessionId: Long): F[Unit] =
        Instrumented.trace("SessionRepo.touchSession", m, Attribute("session.id", sessionId))(
          pTouch.execute(sessionId).void
        )

      def findById(sessionId: Long): F[Option[AuthSession]] =
        Instrumented.trace("SessionRepo.findById", m, Attribute("session.id", sessionId))(
          pFindById.option(sessionId)
        )

    }

}
