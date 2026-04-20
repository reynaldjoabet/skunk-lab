package kudi.service

import cats.effect.*
import cats.syntax.all.*

import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait AuthService[F[_]] {

  def login(
    email: String,
    password: String,
    ipAddress: String,
    userAgent: Option[String]
  ): F[PasswordVerifyResult]

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
  def getActiveSessions(userId: Long): F[List[AuthSession]]
  def touchSession(sessionId: Long): F[Unit]
  def changePassword(userId: Long, newHash: String, currentSessionId: Option[Long]): F[Unit]

}

object AuthService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    m: Meter[F]
  ): F[AuthService[F]] =
    for {
      met <- Instrumented.makeMetrics[F]("service.auth")
      loginCount <- m.counter[Long]("service.auth.logins")
                      .withUnit("{logins}")
                      .withDescription("Login attempts by outcome")
                      .create
      creds    <- CredentialRepo.fromSession(s)
      sessions <- SessionRepo.fromSession(s)
      attempts <- LoginAttemptRepo.fromSession(s)
      audit    <- AuditRepo.fromSession(s)
    } yield new AuthService[F] {

      private def countLogin(outcome: String): F[Unit] =
        loginCount.add(1L, Attribute("login.outcome", outcome))

      def login(
        email: String,
        password: String,
        ipAddress: String,
        userAgent: Option[String]
      ): F[PasswordVerifyResult] =
        Instrumented.trace("AuthService.login", met) {
          for {
            result <- creds.verifyPassword(email, password)
            _ <- attempts.record(
                   userId = result.userId,
                   success = result.isValid,
                   identifier = email,
                   ipAddress = ipAddress,
                   userAgent = userAgent,
                   failureReason =
                     if (result.isLocked) Some("account_locked")
                     else if (!result.isValid) Some("invalid_password")
                     else None
                 )
            _ <- if (result.isValid) countLogin("success")
                 else if (result.isLocked) countLogin("locked")
                 else countLogin("invalid")
            _ <- audit.log(
                   actorId = result.userId,
                   action = if (result.isValid) "login_success" else "login_failed",
                   entityType = "user",
                   entityId = result.userId.map(_.toString).getOrElse("unknown"),
                   ipAddress = Some(ipAddress),
                   oldValues = None,
                   newValues = None
                 )
          } yield result
        }

      def createSession(
        userId: Long,
        tokenHash: Array[Byte],
        ipAddress: String,
        userAgent: Option[String],
        deviceFingerprint: Option[String],
        ttlHours: Int
      ): F[Long] =
        Instrumented.trace("AuthService.createSession", met, Attribute("user.id", userId))(
          for {
            sid <-
              sessions
                .createSession(userId, tokenHash, ipAddress, userAgent, deviceFingerprint, ttlHours)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "session_created",
                   entityType = "session",
                   entityId = sid.toString,
                   ipAddress = Some(ipAddress),
                   oldValues = None,
                   newValues = None
                 )
          } yield sid
        )

      def revokeSession(sessionId: Long, reasonId: SessionRevokeReasonId): F[Unit] =
        Instrumented.trace("AuthService.revokeSession", met, Attribute("session.id", sessionId))(
          for {
            _ <- sessions.revokeSession(sessionId, reasonId)
            _ <- audit.log(
                   actorId = None,
                   action = "session_revoked",
                   entityType = "session",
                   entityId = sessionId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def revokeAllSessions(userId: Long, reasonId: SessionRevokeReasonId): F[Int] =
        Instrumented.trace("AuthService.revokeAllSessions", met, Attribute("user.id", userId))(
          for {
            count <- sessions.revokeAllSessions(userId, reasonId)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "all_sessions_revoked",
                   entityType = "user",
                   entityId = userId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield count
        )

      def getActiveSessions(userId: Long): F[List[AuthSession]] =
        Instrumented.trace("AuthService.getActiveSessions", met, Attribute("user.id", userId))(
          sessions.findActiveSessions(userId)
        )

      def touchSession(sessionId: Long): F[Unit] =
        Instrumented.trace("AuthService.touchSession", met, Attribute("session.id", sessionId))(
          sessions.touchSession(sessionId)
        )

      def changePassword(userId: Long, newHash: String, currentSessionId: Option[Long]): F[Unit] =
        Instrumented.trace("AuthService.changePassword", met, Attribute("user.id", userId))(
          for {
            _ <- creds.changePassword(userId, newHash)
            _ <- sessions.revokeAllSessions(userId, SessionRevokeReasonId.PasswordChange)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "password_changed",
                   entityType = "user",
                   entityId = userId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

    }

}
