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

trait IdentityResourceRepo[F[_]] {

  def findById(id: Int): F[Option[IdentityResource]]
  def findByName(name: String): F[Option[IdentityResource]]
  def create(cmd: CreateIdentityResource): F[IdentityResource]
  def delete(id: Int): F[Unit]

}

object IdentityResourceRepo {

  private val cols =
    "id, name, display_name, description, enabled, required, emphasize, " +
      "show_in_discovery_document, created, updated, non_editable"

  private object SQL {

    val findById: Query[Int, IdentityResource] =
      sql"SELECT #$cols FROM identity_resources WHERE id = $int4".query(identityResource)

    val findByName: Query[String, IdentityResource] =
      sql"SELECT #$cols FROM identity_resources WHERE name = $text".query(identityResource)

    val insert: Query[String *: Option[String] *: Option[String] *: EmptyTuple, IdentityResource] =
      sql"""
        INSERT INTO identity_resources (name, display_name, description)
        VALUES ($text, ${text.opt}, ${text.opt})
        RETURNING #$cols
      """.query(identityResource)

    val delete: Command[Int] =
      sql"DELETE FROM identity_resources WHERE id = $int4".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[IdentityResourceRepo[F]] =
    for {
      m           <- Instrumented.makeMetrics[F]("repo.identity_resource")
      pFindById   <- s.prepare(SQL.findById)
      pFindByName <- s.prepare(SQL.findByName)
      pInsert     <- s.prepare(SQL.insert)
      pDelete     <- s.prepare(SQL.delete)
    } yield new IdentityResourceRepo[F] {

      def findById(id: Int): F[Option[IdentityResource]] =
        Instrumented.trace(
          "IdentityResourceRepo.findById",
          m,
          Attribute("identity_resource.id", id.toLong)
        )(
          pFindById.option(id)
        )

      def findByName(name: String): F[Option[IdentityResource]] =
        Instrumented.trace("IdentityResourceRepo.findByName", m)(
          pFindByName.option(name)
        )

      def create(cmd: CreateIdentityResource): F[IdentityResource] =
        Instrumented.trace("IdentityResourceRepo.create", m)(
          pInsert.unique((cmd.name, cmd.displayName, cmd.description))
        )

      def delete(id: Int): F[Unit] =
        Instrumented.trace(
          "IdentityResourceRepo.delete",
          m,
          Attribute("identity_resource.id", id.toLong)
        )(
          pDelete.execute(id).void
        )

    }

}
