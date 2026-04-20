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

trait ServerSideSessionRepo[F[_]] {

  def findByKey(key: String): F[Option[ServerSideSession]]
  def findBySubjectId(subjectId: String): F[List[ServerSideSession]]
  def create(cmd: CreateServerSideSession): F[ServerSideSession]
  def deleteByKey(key: String): F[Unit]
  def renewSession(key: String): F[Unit]
  def deleteExpired: F[Unit]

}

object ServerSideSessionRepo {

  private val cols =
    "id, key, scheme, subject_id, session_id, display_name, created, renewed, expires, data"

  private object SQL {

    val findByKey: Query[String, ServerSideSession] =
      sql"SELECT #$cols FROM idmgmt.server_side_sessions WHERE key = $text".query(serverSideSession)

    val findBySubjectId: Query[String, ServerSideSession] =
      sql"SELECT #$cols FROM idmgmt.server_side_sessions WHERE subject_id = $text"
        .query(serverSideSession)

    val insert: Query[
      String *: String *: String *: Option[String] *: Option[String] *: String *: EmptyTuple,
      ServerSideSession
    ] =
      sql"""
        INSERT INTO idmgmt.server_side_sessions (key, scheme, subject_id, session_id, display_name, data)
        VALUES ($text, $text, $text, ${text.opt}, ${text.opt}, $text)
        RETURNING #$cols
      """.query(serverSideSession)

    val deleteByKey: Command[String] =
      sql"DELETE FROM idmgmt.server_side_sessions WHERE key = $text".command

    val renewSession: Command[String] =
      sql"UPDATE idmgmt.server_side_sessions SET renewed = now() WHERE key = $text".command

    val deleteExpired: Command[Void] =
      sql"DELETE FROM idmgmt.server_side_sessions WHERE expires IS NOT NULL AND expires < now()"
        .command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[ServerSideSessionRepo[F]] =
    for {
      m              <- Instrumented.makeMetrics[F]("repo.server_side_session")
      pFindByKey     <- s.prepare(SQL.findByKey)
      pFindBySub     <- s.prepare(SQL.findBySubjectId)
      pInsert        <- s.prepare(SQL.insert)
      pDeleteByKey   <- s.prepare(SQL.deleteByKey)
      pRenew         <- s.prepare(SQL.renewSession)
      pDeleteExpired <- s.prepare(SQL.deleteExpired)
    } yield new ServerSideSessionRepo[F] {

      def findByKey(key: String): F[Option[ServerSideSession]] =
        Instrumented.trace("ServerSideSessionRepo.findByKey", m)(
          pFindByKey.option(key)
        )

      def findBySubjectId(subjectId: String): F[List[ServerSideSession]] =
        Instrumented.trace("ServerSideSessionRepo.findBySubjectId", m)(
          pFindBySub.stream(subjectId, 64).compile.toList
        )

      def create(cmd: CreateServerSideSession): F[ServerSideSession] =
        Instrumented.trace("ServerSideSessionRepo.create", m)(
          pInsert.unique(
            (cmd.key, cmd.scheme, cmd.subjectId, cmd.sessionId, cmd.displayName, cmd.data)
          )
        )

      def deleteByKey(key: String): F[Unit] =
        Instrumented.trace("ServerSideSessionRepo.deleteByKey", m)(
          pDeleteByKey.execute(key).void
        )

      def renewSession(key: String): F[Unit] =
        Instrumented.trace("ServerSideSessionRepo.renewSession", m)(
          pRenew.execute(key).void
        )

      def deleteExpired: F[Unit] =
        Instrumented.trace("ServerSideSessionRepo.deleteExpired", m)(
          pDeleteExpired.execute(Void).void
        )

    }

}
