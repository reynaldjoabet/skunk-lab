package ledgerpay.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._
import fs2.Stream

import ledgerpay.domain._
import ledgerpay.repo._
import skunk.circe.codec.all.jsonb
import skunk.Session

trait AccountService[F[_]] {

  def open(userId: UUID, currency: CurrencyCode): F[Account]
  def getAccount(id: UUID): F[Option[Account]]
  def getUserAccounts(userId: UUID): F[List[Account]]
  def getHistory(accountId: UUID): Stream[F, Transaction]
  def freezeAccount(id: UUID): F[Unit]

}

object AccountService {

  def fromSession[F[_]: Concurrent](s: Session[F]): F[AccountService[F]] =
    (AccountRepo.fromSession(s), TransactionRepo.fromSession(s)).mapN { (accounts, txns) =>
      new AccountService[F] {

        def open(userId: UUID, currency: CurrencyCode): F[Account] =
          accounts.create(CreateAccount(userId, currency))

        def getAccount(id: UUID): F[Option[Account]] =
          accounts.findById(id)

        def getUserAccounts(userId: UUID): F[List[Account]] =
          accounts.findByUser(userId)

        def getHistory(accountId: UUID): Stream[F, Transaction] =
          txns.findByAccount(accountId)

        def freezeAccount(id: UUID): F[Unit] =
          accounts.freeze(id)

      }
    }

}
