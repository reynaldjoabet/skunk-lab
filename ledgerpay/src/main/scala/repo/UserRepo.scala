package ledgerpay.repo

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import ledgerpay.db.Codecs._
import ledgerpay.domain._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait UserRepo[F[_]] {

  def create(cmd: CreateUser): F[User]
  def findById(id: UUID): F[Option[User]]
  def findByEmail(email: String): F[Option[User]]
  def updateKycLevel(id: UUID, level: KycLevel): F[Unit]

}

object UserRepo {

  private object SQL {

    val insert: Query[String *: String *: EmptyTuple, User] =
      sql"""
        INSERT INTO users (email, full_name)
        VALUES ($varchar, $varchar)
        RETURNING id, email, full_name, kyc_level, created_at, updated_at
      """.query(user)

    val findById: Query[UUID, User] =
      sql"SELECT id, email, full_name, kyc_level, created_at, updated_at FROM users WHERE id = $uuid"
        .query(user)

    val findByEmail: Query[String, User] =
      sql"SELECT id, email, full_name, kyc_level, created_at, updated_at FROM users WHERE email = $varchar"
        .query(user)

    val updateKyc: Command[KycLevel *: UUID *: EmptyTuple] =
      sql"UPDATE users SET kyc_level = $kycLevel, updated_at = now() WHERE id = $uuid".command

  }

  def fromSession[F[_]: MonadCancelThrow](s: Session[F]): F[UserRepo[F]] =
    (
      s.prepare(SQL.insert),
      s.prepare(SQL.findById),
      s.prepare(SQL.findByEmail),
      s.prepare(SQL.updateKyc)
    ).mapN { (pInsert, pFindById, pFindByEmail, pUpdateKyc) =>
      new UserRepo[F] {

        def create(cmd: CreateUser): F[User] =
          pInsert.unique((cmd.email, cmd.fullName))

        def findById(id: UUID): F[Option[User]] =
          pFindById.option(id)

        def findByEmail(email: String): F[Option[User]] =
          pFindByEmail.option(email)

        def updateKycLevel(id: UUID, level: KycLevel): F[Unit] =
          pUpdateKyc.execute((level, id)).void

      }
    }

}
