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

trait PersistedGrantRepo[F[_]] {

  def findByKey(key: String): F[Option[PersistedGrant]]
  def findBySubjectAndClient(subjectId: String, clientId: String): F[List[PersistedGrant]]
  def create(cmd: CreatePersistedGrant): F[PersistedGrant]
  def deleteByKey(key: String): F[Unit]
  def deleteExpired: F[Unit]

}

object PersistedGrantRepo {

  private val cols =
    "id, key, type, subject_id, session_id, client_id, description, creation_time, expiration, consumed_time, data"

  private object SQL {

    val findByKey: Query[String, PersistedGrant] =
      sql"SELECT #$cols FROM idmgmt.persisted_grants WHERE key = $text".query(persistedGrant)

    val findBySubjectAndClient: Query[String *: String *: EmptyTuple, PersistedGrant] =
      sql"SELECT #$cols FROM idmgmt.persisted_grants WHERE subject_id = $text AND client_id = $text"
        .query(persistedGrant)

    val insert: Query[
      Option[String] *: String *: Option[String] *: Option[String] *: String *:
        Option[
          String
        ] *: java.time.OffsetDateTime *: Option[java.time.OffsetDateTime] *: String *: EmptyTuple,
      PersistedGrant
    ] =
      sql"""
        INSERT INTO idmgmt.persisted_grants (key, type, subject_id, session_id, client_id, description, creation_time, expiration, data)
        VALUES (${text.opt}, $text, ${text.opt}, ${text.opt}, $text, ${text
          .opt}, $timestamptz, ${timestamptz.opt}, $text)
        RETURNING #$cols
      """.query(persistedGrant)

    val deleteByKey: Command[String] =
      sql"DELETE FROM idmgmt.persisted_grants WHERE key = $text".command

    val deleteExpired: Command[Void] =
      sql"DELETE FROM idmgmt.persisted_grants WHERE expiration IS NOT NULL AND expiration < now()"
        .command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[PersistedGrantRepo[F]] =
    for {
      m              <- Instrumented.makeMetrics[F]("repo.persisted_grant")
      pFindByKey     <- s.prepare(SQL.findByKey)
      pFindBySC      <- s.prepare(SQL.findBySubjectAndClient)
      pInsert        <- s.prepare(SQL.insert)
      pDeleteByKey   <- s.prepare(SQL.deleteByKey)
      pDeleteExpired <- s.prepare(SQL.deleteExpired)
    } yield new PersistedGrantRepo[F] {

      def findByKey(key: String): F[Option[PersistedGrant]] =
        Instrumented.trace("PersistedGrantRepo.findByKey", m)(
          pFindByKey.option(key)
        )

      def findBySubjectAndClient(subjectId: String, clientId: String): F[List[PersistedGrant]] =
        Instrumented.trace("PersistedGrantRepo.findBySubjectAndClient", m)(
          pFindBySC.stream((subjectId, clientId), 64).compile.toList
        )

      def create(cmd: CreatePersistedGrant): F[PersistedGrant] =
        Instrumented.trace("PersistedGrantRepo.create", m)(
          pInsert.unique(
            (
              cmd.key,
              cmd.grantType,
              cmd.subjectId,
              cmd.sessionId,
              cmd.clientId,
              cmd.description,
              cmd.creationTime,
              cmd.expiration,
              cmd.data
            )
          )
        )

      def deleteByKey(key: String): F[Unit] =
        Instrumented.trace("PersistedGrantRepo.deleteByKey", m)(
          pDeleteByKey.execute(key).void
        )

      def deleteExpired: F[Unit] =
        Instrumented.trace("PersistedGrantRepo.deleteExpired", m)(
          pDeleteExpired.execute(Void).void
        )

    }

}
