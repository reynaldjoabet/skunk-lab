package idmgmt.repo

import cats.effect.*
import cats.syntax.all.*

import idmgmt.db.Codecs.*
import idmgmt.domain.*
import idmgmt.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait ApiResourceRepo[F[_]] {

  def findById(id: Int): F[Option[ApiResource]]
  def findByName(name: String): F[Option[ApiResource]]
  def create(cmd: CreateApiResource): F[ApiResource]
  def delete(id: Int): F[Unit]
  def listScopes(resourceId: Int): F[List[ApiResourceScope]]
  def addScope(resourceId: Int, scope: String): F[ApiResourceScope]
  def removeScope(resourceId: Int, scope: String): F[Unit]

}

object ApiResourceRepo {

  private val cols =
    "id, name, display_name, description, enabled, show_in_discovery_document, " +
      "require_resource_indicator, allowed_access_token_signing_algorithms, " +
      "created, updated, last_accessed, non_editable"

  private object SQL {

    val findById: Query[Int, ApiResource] =
      sql"SELECT #$cols FROM api_resources WHERE id = $int4".query(apiResource)

    val findByName: Query[String, ApiResource] =
      sql"SELECT #$cols FROM api_resources WHERE name = $text".query(apiResource)

    val insert: Query[String *: Option[String] *: Option[String] *: EmptyTuple, ApiResource] =
      sql"""
        INSERT INTO api_resources (name, display_name, description)
        VALUES ($text, ${text.opt}, ${text.opt})
        RETURNING #$cols
      """.query(apiResource)

    val delete: Command[Int] =
      sql"DELETE FROM api_resources WHERE id = $int4".command

    val listScopes: Query[Int, ApiResourceScope] =
      sql"SELECT id, scope, api_resource_id FROM api_resource_scopes WHERE api_resource_id = $int4"
        .query(apiResourceScope)

    val addScope: Query[String *: Int *: EmptyTuple, ApiResourceScope] =
      sql"INSERT INTO api_resource_scopes (scope, api_resource_id) VALUES ($text, $int4) RETURNING id, scope, api_resource_id"
        .query(apiResourceScope)

    val removeScope: Command[String *: Int *: EmptyTuple] =
      sql"DELETE FROM api_resource_scopes WHERE scope = $text AND api_resource_id = $int4".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ApiResourceRepo[F]] =
    for {
      m            <- Instrumented.makeMetrics[F]("repo.api_resource")
      pFindById    <- s.prepare(SQL.findById)
      pFindByName  <- s.prepare(SQL.findByName)
      pInsert      <- s.prepare(SQL.insert)
      pDelete      <- s.prepare(SQL.delete)
      pListScopes  <- s.prepare(SQL.listScopes)
      pAddScope    <- s.prepare(SQL.addScope)
      pRemoveScope <- s.prepare(SQL.removeScope)
    } yield new ApiResourceRepo[F] {

      def findById(id: Int): F[Option[ApiResource]] =
        Instrumented.trace("ApiResourceRepo.findById", m, Attribute("api_resource.id", id.toLong))(
          pFindById.option(id)
        )

      def findByName(name: String): F[Option[ApiResource]] =
        Instrumented.trace("ApiResourceRepo.findByName", m)(
          pFindByName.option(name)
        )

      def create(cmd: CreateApiResource): F[ApiResource] =
        Instrumented.trace("ApiResourceRepo.create", m)(
          pInsert.unique((cmd.name, cmd.displayName, cmd.description))
        )

      def delete(id: Int): F[Unit] =
        Instrumented.trace("ApiResourceRepo.delete", m, Attribute("api_resource.id", id.toLong))(
          pDelete.execute(id).void
        )

      def listScopes(resourceId: Int): F[List[ApiResourceScope]] =
        Instrumented.trace("ApiResourceRepo.listScopes", m)(
          pListScopes.stream(resourceId, 64).compile.toList
        )

      def addScope(resourceId: Int, scope: String): F[ApiResourceScope] =
        Instrumented.trace("ApiResourceRepo.addScope", m)(
          pAddScope.unique((scope, resourceId))
        )

      def removeScope(resourceId: Int, scope: String): F[Unit] =
        Instrumented.trace("ApiResourceRepo.removeScope", m)(
          pRemoveScope.execute((scope, resourceId)).void
        )

    }

}
