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

trait RbacService[F[_]] {

  def assignRole(
    userId: Long,
    roleId: RoleId,
    grantedBy: Option[Long],
    expiresAt: Option[java.time.OffsetDateTime]
  ): F[Unit]

  def revokeRole(userId: Long, roleId: RoleId): F[Unit]
  def getUserRoles(userId: Long): F[List[Role]]
  def getUserPermissions(userId: Long): F[List[Permission]]
  def hasPermission(userId: Long, resource: String, action: String): F[Boolean]
  def getAllRoles: F[List[Role]]

}

object RbacService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[RbacService[F]] =
    for {
      m     <- Instrumented.makeMetrics[F]("service.rbac")
      roles <- RoleRepo.fromSession(s)
      audit <- AuditRepo.fromSession(s)
    } yield new RbacService[F] {

      def assignRole(
        userId: Long,
        roleId: RoleId,
        grantedBy: Option[Long],
        expiresAt: Option[java.time.OffsetDateTime]
      ): F[Unit] =
        Instrumented.trace(
          "RbacService.assignRole",
          m,
          Attribute("user.id", userId),
          Attribute("role.id", roleId.value.toLong)
        )(
          for {
            _ <- roles.assignRole(userId, roleId, grantedBy, expiresAt)
            _ <- audit.log(
                   actorId = grantedBy,
                   action = "role_assigned",
                   entityType = "user_role",
                   entityId = s"$userId:${roleId.value}",
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def revokeRole(userId: Long, roleId: RoleId): F[Unit] =
        Instrumented.trace(
          "RbacService.revokeRole",
          m,
          Attribute("user.id", userId),
          Attribute("role.id", roleId.value.toLong)
        )(
          for {
            _ <- roles.revokeRole(userId, roleId)
            _ <- audit.log(
                   actorId = None,
                   action = "role_revoked",
                   entityType = "user_role",
                   entityId = s"$userId:${roleId.value}",
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def getUserRoles(userId: Long): F[List[Role]] =
        Instrumented.trace("RbacService.getUserRoles", m, Attribute("user.id", userId))(
          roles.getUserRoles(userId)
        )

      def getUserPermissions(userId: Long): F[List[Permission]] =
        Instrumented.trace("RbacService.getUserPermissions", m, Attribute("user.id", userId))(
          roles.getUserPermissions(userId)
        )

      def hasPermission(userId: Long, resource: String, action: String): F[Boolean] =
        Instrumented.trace("RbacService.hasPermission", m, Attribute("user.id", userId))(
          roles.hasPermission(userId, resource, action)
        )

      def getAllRoles: F[List[Role]] =
        Instrumented.trace("RbacService.getAllRoles", m)(
          roles.getAllRoles
        )

    }

}
