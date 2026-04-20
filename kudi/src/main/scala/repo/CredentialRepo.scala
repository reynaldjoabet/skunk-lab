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

trait CredentialRepo[F[_]] {

  def verifyPassword(email: String, password: String): F[PasswordVerifyResult]
  def changePassword(userId: Long, newHash: String): F[Unit]

}

object CredentialRepo {

  private object SQL {

    val verifyPassword: Query[String *: String *: EmptyTuple, PasswordVerifyResult] =
      sql"""SELECT * FROM auth.verify_password($text, $text)""".query(passwordVerifyResult)

    val changePassword: Command[String *: Long *: EmptyTuple] =
      sql"""
        UPDATE auth.credentials
        SET password_hash = $text, password_changed_at = now(), must_change_password = false
        WHERE user_id = $int8
      """.command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[CredentialRepo[F]] =
    for {
      m       <- Instrumented.makeMetrics[F]("repo.credential")
      pVerify <- s.prepare(SQL.verifyPassword)
      pChange <- s.prepare(SQL.changePassword)
    } yield new CredentialRepo[F] {

      def verifyPassword(email: String, password: String): F[PasswordVerifyResult] =
        Instrumented.trace("CredentialRepo.verifyPassword", m)(
          pVerify.unique((email, password))
        )

      def changePassword(userId: Long, newHash: String): F[Unit] =
        Instrumented.trace("CredentialRepo.changePassword", m, Attribute("user.id", userId))(
          pChange.execute((newHash, userId)).void
        )

    }

}
