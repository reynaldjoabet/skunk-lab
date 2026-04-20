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

trait KeyRepo[F[_]] {

  def findById(id: String): F[Option[SigningKey]]
  def findByUse(use: String): F[List[SigningKey]]
  def create(cmd: CreateSigningKey): F[SigningKey]
  def delete(id: String): F[Unit]

}

object KeyRepo {

  private val cols =
    "id, version, created, use, algorithm, is_x509_certificate, data_protected, data"

  private object SQL {

    val findById: Query[String, SigningKey] =
      sql"SELECT #$cols FROM idmgmt.keys WHERE id = $text".query(signingKey)

    val findByUse: Query[String, SigningKey] =
      sql"SELECT #$cols FROM idmgmt.keys WHERE use = $text".query(signingKey)

    val insert: Query[
      String *: Int *: Option[String] *: String *: Boolean *: Boolean *: String *: EmptyTuple,
      SigningKey
    ] =
      sql"""
        INSERT INTO idmgmt.keys (id, version, use, algorithm, is_x509_certificate, data_protected, data)
        VALUES ($text, $int4, ${text.opt}, $text, $bool, $bool, $text)
        RETURNING #$cols
      """.query(signingKey)

    val delete: Command[String] =
      sql"DELETE FROM idmgmt.keys WHERE id = $text".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[KeyRepo[F]] =
    for {
      m          <- Instrumented.makeMetrics[F]("repo.key")
      pFindById  <- s.prepare(SQL.findById)
      pFindByUse <- s.prepare(SQL.findByUse)
      pInsert    <- s.prepare(SQL.insert)
      pDelete    <- s.prepare(SQL.delete)
    } yield new KeyRepo[F] {

      def findById(id: String): F[Option[SigningKey]] =
        Instrumented.trace("KeyRepo.findById", m)(
          pFindById.option(id)
        )

      def findByUse(use: String): F[List[SigningKey]] =
        Instrumented.trace("KeyRepo.findByUse", m)(
          pFindByUse.stream(use, 64).compile.toList
        )

      def create(cmd: CreateSigningKey): F[SigningKey] =
        Instrumented.trace("KeyRepo.create", m)(
          pInsert.unique(
            (
              cmd.id,
              cmd.version,
              cmd.use,
              cmd.algorithm,
              cmd.isX509Certificate,
              cmd.dataProtected,
              cmd.data
            )
          )
        )

      def delete(id: String): F[Unit] =
        Instrumented.trace("KeyRepo.delete", m)(
          pDelete.execute(id).void
        )

    }

}
