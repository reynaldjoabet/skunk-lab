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

trait ApiScopeRepo[F[_]] {

  def findById(id: Int): F[Option[ApiScope]]
  def findByName(name: String): F[Option[ApiScope]]
  def create(cmd: CreateApiScope): F[ApiScope]
  def delete(id: Int): F[Unit]

}

object ApiScopeRepo {

  private val cols =
    "id, name, display_name, description, enabled, required, emphasize, " +
      "show_in_discovery_document, created, updated, last_accessed, non_editable"

  private object SQL {

    val findById: Query[Int, ApiScope] =
      sql"SELECT #$cols FROM api_scopes WHERE id = $int4".query(apiScope)

    val findByName: Query[String, ApiScope] =
      sql"SELECT #$cols FROM api_scopes WHERE name = $text".query(apiScope)

    val insert: Query[String *: Option[String] *: Option[String] *: EmptyTuple, ApiScope] =
      sql"""
        INSERT INTO api_scopes (name, display_name, description)
        VALUES ($text, ${text.opt}, ${text.opt})
        RETURNING #$cols
      """.query(apiScope)

    val delete: Command[Int] =
      sql"DELETE FROM api_scopes WHERE id = $int4".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ApiScopeRepo[F]] =
    for {
      m           <- Instrumented.makeMetrics[F]("repo.api_scope")
      pFindById   <- s.prepare(SQL.findById)
      pFindByName <- s.prepare(SQL.findByName)
      pInsert     <- s.prepare(SQL.insert)
      pDelete     <- s.prepare(SQL.delete)
    } yield new ApiScopeRepo[F] {

      def findById(id: Int): F[Option[ApiScope]] =
        Instrumented.trace("ApiScopeRepo.findById", m, Attribute("api_scope.id", id.toLong))(
          pFindById.option(id)
        )

      def findByName(name: String): F[Option[ApiScope]] =
        Instrumented.trace("ApiScopeRepo.findByName", m)(
          pFindByName.option(name)
        )

      def create(cmd: CreateApiScope): F[ApiScope] =
        Instrumented.trace("ApiScopeRepo.create", m)(
          pInsert.unique((cmd.name, cmd.displayName, cmd.description))
        )

      def delete(id: Int): F[Unit] =
        Instrumented.trace("ApiScopeRepo.delete", m, Attribute("api_scope.id", id.toLong))(
          pDelete.execute(id).void
        )

    }

}
