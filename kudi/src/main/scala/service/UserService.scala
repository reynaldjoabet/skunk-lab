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

trait UserService[F[_]] {

  def register(cmd: CreateUser): F[User]
  def getUser(userId: Long): F[Option[User]]
  def getUserByEmail(email: String): F[Option[User]]
  def updateKycStatus(userId: Long, statusId: KycStatusId): F[Unit]
  def blockUser(userId: Long): F[Unit]
  def unblockUser(userId: Long): F[Unit]

}

object UserService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[UserService[F]] =
    for {
      m     <- Instrumented.makeMetrics[F]("service.user")
      repo  <- UserRepo.fromSession(s)
      audit <- AuditRepo.fromSession(s)
    } yield new UserService[F] {

      def register(cmd: CreateUser): F[User] =
        Instrumented.trace("UserService.register", m)(
          for {
            user <- repo.create(cmd)
            _ <- audit.log(
                   actorId = Some(user.userId),
                   action = "user_registered",
                   entityType = "user",
                   entityId = user.userId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield user
        )

      def getUser(userId: Long): F[Option[User]] =
        Instrumented.trace("UserService.getUser", m, Attribute("user.id", userId))(
          repo.findById(userId)
        )

      def getUserByEmail(email: String): F[Option[User]] =
        Instrumented.trace("UserService.getUserByEmail", m)(
          repo.findByEmail(email)
        )

      def updateKycStatus(userId: Long, statusId: KycStatusId): F[Unit] =
        Instrumented.trace("UserService.updateKycStatus", m, Attribute("user.id", userId))(
          for {
            _ <- repo.updateKycStatus(userId, statusId)
            _ <- audit.log(
                   actorId = None,
                   action = "kyc_status_updated",
                   entityType = "user",
                   entityId = userId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def blockUser(userId: Long): F[Unit] =
        Instrumented.trace("UserService.blockUser", m, Attribute("user.id", userId))(
          for {
            _ <- repo.block(userId)
            _ <- audit.log(
                   actorId = None,
                   action = "user_blocked",
                   entityType = "user",
                   entityId = userId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def unblockUser(userId: Long): F[Unit] =
        Instrumented.trace("UserService.unblockUser", m, Attribute("user.id", userId))(
          for {
            _ <- repo.unblock(userId)
            _ <- audit.log(
                   actorId = None,
                   action = "user_unblocked",
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
