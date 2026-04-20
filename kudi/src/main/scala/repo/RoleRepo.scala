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

trait RoleRepo[F[_]] {

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

object RoleRepo {

  private val roleCols = "role_id, is_system, role_name, description, created_at"
  private val permCols = "permission_id, resource, action, description"

  private object SQL {

    val assignRole: Command[
      Long *: Option[Long] *: Option[java.time.OffsetDateTime] *: Short *: EmptyTuple
    ] =
      sql"""
        INSERT INTO core.user_roles (user_id, granted_by, expires_at, role_id)
        VALUES ($int8, ${int8.opt}, ${timestamptz.opt}, $int2)
        ON CONFLICT (user_id, role_id) DO UPDATE SET
          granted_by = EXCLUDED.granted_by,
          granted_at = now(),
          expires_at = EXCLUDED.expires_at
      """.command

    val revokeRole: Command[Long *: Short *: EmptyTuple] =
      sql"""DELETE FROM core.user_roles WHERE user_id = $int8 AND role_id = $int2""".command

    val getUserRoles: Query[Long, Role] =
      sql"""
        SELECT r.#$roleCols FROM core.roles r
        JOIN core.user_roles ur ON ur.role_id = r.role_id
        WHERE ur.user_id = $int8
          AND (ur.expires_at IS NULL OR ur.expires_at > now())
        ORDER BY r.role_id
      """.query(role)

    val getUserPermissions: Query[Long, Permission] =
      sql"""
        SELECT DISTINCT p.#$permCols FROM core.permissions p
        JOIN core.role_permissions rp ON rp.permission_id = p.permission_id
        JOIN core.user_roles ur ON ur.role_id = rp.role_id
        WHERE ur.user_id = $int8
          AND (ur.expires_at IS NULL OR ur.expires_at > now())
        ORDER BY p.permission_id
      """.query(permission)

    val hasPermission: Query[Long *: String *: String *: EmptyTuple, Boolean] =
      sql"""
        SELECT EXISTS(
          SELECT 1 FROM core.permissions p
          JOIN core.role_permissions rp ON rp.permission_id = p.permission_id
          JOIN core.user_roles ur ON ur.role_id = rp.role_id
          WHERE ur.user_id = $int8 AND p.resource = $text AND p.action = $text
            AND (ur.expires_at IS NULL OR ur.expires_at > now())
        )
      """.query(bool)

    val getAllRoles: Query[Void, Role] =
      sql"""SELECT #$roleCols FROM core.roles ORDER BY role_id""".query(role)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[RoleRepo[F]] =
    for {
      m        <- Instrumented.makeMetrics[F]("repo.role")
      pAssign  <- s.prepare(SQL.assignRole)
      pRevoke  <- s.prepare(SQL.revokeRole)
      pRoles   <- s.prepare(SQL.getUserRoles)
      pPerms   <- s.prepare(SQL.getUserPermissions)
      pHasPerm <- s.prepare(SQL.hasPermission)
    } yield new RoleRepo[F] {

      def assignRole(
        userId: Long,
        roleId: RoleId,
        grantedBy: Option[Long],
        expiresAt: Option[java.time.OffsetDateTime]
      ): F[Unit] =
        Instrumented.trace(
          "RoleRepo.assignRole",
          m,
          Attribute("user.id", userId),
          Attribute("role.id", roleId.value.toLong)
        )(
          pAssign.execute((userId, grantedBy, expiresAt, roleId.value)).void
        )

      def revokeRole(userId: Long, roleId: RoleId): F[Unit] =
        Instrumented.trace(
          "RoleRepo.revokeRole",
          m,
          Attribute("user.id", userId),
          Attribute("role.id", roleId.value.toLong)
        )(
          pRevoke.execute((userId, roleId.value)).void
        )

      def getUserRoles(userId: Long): F[List[Role]] =
        Instrumented.trace("RoleRepo.getUserRoles", m, Attribute("user.id", userId))(
          pRoles.stream(userId, 64).compile.toList
        )

      def getUserPermissions(userId: Long): F[List[Permission]] =
        Instrumented.trace("RoleRepo.getUserPermissions", m, Attribute("user.id", userId))(
          pPerms.stream(userId, 64).compile.toList
        )

      def hasPermission(userId: Long, resource: String, action: String): F[Boolean] =
        Instrumented.trace("RoleRepo.hasPermission", m, Attribute("user.id", userId))(
          pHasPerm.unique((userId, resource, action))
        )

      def getAllRoles: F[List[Role]] =
        Instrumented.trace("RoleRepo.getAllRoles", m)(
          s.execute(SQL.getAllRoles)
        )

    }

}
