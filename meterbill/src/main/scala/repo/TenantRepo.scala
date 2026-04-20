package meterbill.repo

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import io.circe.Json
import meterbill.db.Codecs._
import meterbill.domain._
import skunk._
import skunk.circe.codec.json.jsonb
import skunk.codec.all._
import skunk.implicits._

trait TenantRepo[F[_]] {

  def create(slug: String, name: String, email: String): F[Tenant]
  def findById(id: UUID): F[Option[Tenant]]
  def findBySlug(slug: String): F[Option[Tenant]]

}

object TenantRepo {

  private object SQL {

    val insert: Query[String *: String *: String *: EmptyTuple, Tenant] =
      sql"""
        INSERT INTO tenants (slug, name, email)
        VALUES ($varchar, $varchar, $varchar)
        RETURNING id, slug, name, email, metadata, created_at
      """.query(tenant)

    val byId: Query[UUID, Tenant] =
      sql"SELECT id, slug, name, email, metadata, created_at FROM tenants WHERE id = $uuid"
        .query(tenant)

    val bySlug: Query[String, Tenant] =
      sql"SELECT id, slug, name, email, metadata, created_at FROM tenants WHERE slug = $varchar"
        .query(tenant)

  }

  def make[F[_]: MonadCancelThrow](s: Session[F]): F[TenantRepo[F]] =
    (s.prepare(SQL.insert), s.prepare(SQL.byId), s.prepare(SQL.bySlug)).mapN {
      (pInsert, pById, pBySlug) =>
        new TenantRepo[F] {

          def create(slug: String, name: String, email: String) = pInsert
            .unique((slug, name, email))

          def findById(id: UUID)       = pById.option(id)
          def findBySlug(slug: String) = pBySlug.option(slug)

        }
    }

}
