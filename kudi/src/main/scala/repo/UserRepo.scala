package kudi.repo

import cats.effect.*
import cats.syntax.all.*

import kudi.db.Codecs.*
import kudi.domain.*
import kudi.Instrumented
import kudi.Instrumented.Metrics
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

trait UserRepo[F[_]] {

  def create(cmd: CreateUser): F[User]
  def findById(id: Long): F[Option[User]]
  def findByEmail(email: String): F[Option[User]]
  def updateKycStatus(id: Long, statusId: KycStatusId): F[Unit]
  def block(id: Long): F[Unit]
  def unblock(id: Long): F[Unit]

}

object UserRepo {

  private val cols =
    "user_id, created_at, updated_at, last_login_at, date_of_birth, risk_score, failed_login_count, kyc_status_id, is_blocked, is_pep, mfa_enabled, email, phone, first_name, last_name, country_code, metadata"

  private object SQL {

    val insert
      : Query[String *: String *: String *: String *: String *: String *: EmptyTuple, User] =
      sql"""
        INSERT INTO users (email, phone, first_name, last_name, country_code, password_hash)
        VALUES ($text, $text, $text, $text, $text, $text)
        RETURNING #$cols
      """.query(user)

    val findById: Query[Long, User] =
      sql"SELECT #$cols FROM users WHERE user_id = $int8".query(user)

    val findByEmail: Query[String, User] =
      sql"SELECT #$cols FROM users WHERE email = $text".query(user)

    val updateKycStatus: Command[Short *: Long *: EmptyTuple] =
      sql"UPDATE users SET kyc_status_id = $int2, updated_at = now() WHERE user_id = $int8".command

    val block: Command[Long] =
      sql"UPDATE users SET is_blocked = true, updated_at = now() WHERE user_id = $int8".command

    val unblock: Command[Long] =
      sql"UPDATE users SET is_blocked = false, updated_at = now() WHERE user_id = $int8".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[UserRepo[F]] =
    for {
      m            <- Instrumented.makeMetrics[F]("repo.user")
      pInsert      <- s.prepare(SQL.insert)
      pFindById    <- s.prepare(SQL.findById)
      pFindByEmail <- s.prepare(SQL.findByEmail)
      pUpdateKyc   <- s.prepare(SQL.updateKycStatus)
      pBlock       <- s.prepare(SQL.block)
      pUnblock     <- s.prepare(SQL.unblock)
    } yield new UserRepo[F] {

      def create(cmd: CreateUser): F[User] =
        Instrumented.trace("UserRepo.create", m)(
          pInsert.unique(
            (cmd.email, cmd.phone, cmd.firstName, cmd.lastName, cmd.countryCode, cmd.passwordHash)
          )
        )

      def findById(id: Long): F[Option[User]] =
        Instrumented.trace("UserRepo.findById", m, Attribute("user.id", id))(
          pFindById.option(id)
        )

      def findByEmail(email: String): F[Option[User]] =
        Instrumented.trace("UserRepo.findByEmail", m)(
          pFindByEmail.option(email)
        )

      def updateKycStatus(id: Long, statusId: KycStatusId): F[Unit] =
        Instrumented.trace("UserRepo.updateKycStatus", m, Attribute("user.id", id))(
          pUpdateKyc.execute((statusId.value, id)).void
        )

      def block(id: Long): F[Unit] =
        Instrumented.trace("UserRepo.block", m, Attribute("user.id", id))(
          pBlock.execute(id).void
        )

      def unblock(id: Long): F[Unit] =
        Instrumented.trace("UserRepo.unblock", m, Attribute("user.id", id))(
          pUnblock.execute(id).void
        )

    }

}
