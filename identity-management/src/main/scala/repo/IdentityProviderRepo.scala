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

trait IdentityProviderRepo[F[_]] {

  def findById(id: Int): F[Option[IdentityProvider]]
  def findByScheme(scheme: String): F[Option[IdentityProvider]]
  def create(cmd: CreateIdentityProvider): F[IdentityProvider]
  def delete(id: Int): F[Unit]
  def setEnabled(id: Int, enabled: Boolean): F[Unit]

}

object IdentityProviderRepo {

  private val cols =
    "id, scheme, display_name, enabled, type, properties, created, updated, last_accessed, non_editable"

  private object SQL {

    val findById: Query[Int, IdentityProvider] =
      sql"SELECT #$cols FROM identity_providers WHERE id = $int4".query(identityProvider)

    val findByScheme: Query[String, IdentityProvider] =
      sql"SELECT #$cols FROM identity_providers WHERE scheme = $text".query(identityProvider)

    val insert: Query[
      String *: Option[String] *: Boolean *: String *: Option[String] *: EmptyTuple,
      IdentityProvider
    ] =
      sql"""
        INSERT INTO identity_providers (scheme, display_name, enabled, type, properties)
        VALUES ($text, ${text.opt}, $bool, $text, ${text.opt})
        RETURNING #$cols
      """.query(identityProvider)

    val delete: Command[Int] =
      sql"DELETE FROM identity_providers WHERE id = $int4".command

    val setEnabled: Command[Boolean *: Int *: EmptyTuple] =
      sql"UPDATE identity_providers SET enabled = $bool, updated = now() WHERE id = $int4".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[IdentityProviderRepo[F]] =
    for {
      m             <- Instrumented.makeMetrics[F]("repo.identity_provider")
      pFindById     <- s.prepare(SQL.findById)
      pFindByScheme <- s.prepare(SQL.findByScheme)
      pInsert       <- s.prepare(SQL.insert)
      pDelete       <- s.prepare(SQL.delete)
      pSetEnabled   <- s.prepare(SQL.setEnabled)
    } yield new IdentityProviderRepo[F] {

      def findById(id: Int): F[Option[IdentityProvider]] =
        Instrumented.trace("IdentityProviderRepo.findById", m, Attribute("idp.id", id.toLong))(
          pFindById.option(id)
        )

      def findByScheme(scheme: String): F[Option[IdentityProvider]] =
        Instrumented.trace("IdentityProviderRepo.findByScheme", m)(
          pFindByScheme.option(scheme)
        )

      def create(cmd: CreateIdentityProvider): F[IdentityProvider] =
        Instrumented.trace("IdentityProviderRepo.create", m)(
          pInsert.unique(
            (cmd.scheme, cmd.displayName, cmd.enabled, cmd.providerType, cmd.properties)
          )
        )

      def delete(id: Int): F[Unit] =
        Instrumented.trace("IdentityProviderRepo.delete", m, Attribute("idp.id", id.toLong))(
          pDelete.execute(id).void
        )

      def setEnabled(id: Int, enabled: Boolean): F[Unit] =
        Instrumented.trace("IdentityProviderRepo.setEnabled", m, Attribute("idp.id", id.toLong))(
          pSetEnabled.execute((enabled, id)).void
        )

    }

}
