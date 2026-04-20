package ledgerpay.repo

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import ledgerpay.db.Codecs._
import ledgerpay.domain._
import skunk.{Transaction as _, *}
import skunk.codec.all._
import skunk.implicits._

trait AccountRepo[F[_]] {

  def create(cmd: CreateAccount): F[Account]
  def findById(id: UUID): F[Option[Account]]
  def findByUser(userId: UUID): F[List[Account]]
  def findByIdForUpdate(id: UUID): F[Option[Account]] // SELECT ... FOR UPDATE
  def updateBalance(id: UUID, newBalance: BigDecimal): F[Unit]
  def freeze(id: UUID): F[Unit]

}

object AccountRepo {

  private object SQL {

    val insert: Query[UUID *: CurrencyCode *: EmptyTuple, Account] =
      sql"""
        INSERT INTO accounts (user_id, currency)
        VALUES ($uuid, $currencyCode)
        RETURNING id, user_id, currency, balance, status, created_at, updated_at
      """.query(account)

    val findById: Query[UUID, Account] =
      sql"""
        SELECT id, user_id, currency, balance, status, created_at, updated_at
        FROM accounts WHERE id = $uuid
      """.query(account)

    val findByUser: Query[UUID, Account] =
      sql"""
        SELECT id, user_id, currency, balance, status, created_at, updated_at
        FROM accounts WHERE user_id = $uuid
      """.query(account)

    // Row-level lock for safe balance updates within a transaction
    val findByIdForUpdate: Query[UUID, Account] =
      sql"""
        SELECT id, user_id, currency, balance, status, created_at, updated_at
        FROM accounts WHERE id = $uuid FOR UPDATE
      """.query(account)

    val updateBalance: Command[BigDecimal *: UUID *: EmptyTuple] =
      sql"UPDATE accounts SET balance = $numeric, updated_at = now() WHERE id = $uuid".command

    val freeze: Command[UUID] =
      sql"UPDATE accounts SET status = 'frozen', updated_at = now() WHERE id = $uuid".command

  }

  def fromSession[F[_]: Concurrent](s: Session[F]): F[AccountRepo[F]] =
    (
      s.prepare(SQL.insert),
      s.prepare(SQL.findById),
      s.prepare(SQL.findByUser),
      s.prepare(SQL.findByIdForUpdate),
      s.prepare(SQL.updateBalance),
      s.prepare(SQL.freeze)
    ).mapN { (pInsert, pFind, pFindByUser, pLock, pUpdateBal, pFreeze) =>
      new AccountRepo[F] {

        def create(cmd: CreateAccount): F[Account] =
          pInsert.unique((cmd.userId, cmd.currency))

        def findById(id: UUID): F[Option[Account]] =
          pFind.option(id)

        def findByUser(userId: UUID): F[List[Account]] =
          pFindByUser.stream(userId, 64).compile.toList

        def findByIdForUpdate(id: UUID): F[Option[Account]] =
          pLock.option(id)

        def updateBalance(id: UUID, newBalance: BigDecimal): F[Unit] =
          pUpdateBal.execute((newBalance, id)).void

        def freeze(id: UUID): F[Unit] =
          pFreeze.execute(id).void

      }
    }

}
