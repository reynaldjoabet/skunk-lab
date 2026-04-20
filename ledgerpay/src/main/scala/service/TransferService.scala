package ledgerpay.service

import java.util.UUID

import cats.effect._
import cats.syntax.all._

import ledgerpay.domain._
import ledgerpay.repo._
import skunk.data.{TransactionAccessMode, TransactionIsolationLevel}
import skunk.Session
import skunk.SqlState

trait TransferService[F[_]] {
  def transfer(req: TransferRequest): F[Transaction]
}

object TransferService {

  sealed trait TransferError extends Throwable

  case class InsufficientFunds(accountId: UUID, available: BigDecimal, requested: BigDecimal)
      extends Exception(s"Insufficient funds: available=$available, requested=$requested")
      with TransferError

  case class AccountNotFound(id: UUID)
      extends Exception(s"Account $id not found")
      with TransferError

  case class AccountFrozen(id: UUID) extends Exception(s"Account $id is frozen") with TransferError

  case class CurrencyMismatch(expected: CurrencyCode, actual: CurrencyCode)
      extends Exception(s"Currency mismatch: expected=${expected.label}, actual=${actual.label}")
      with TransferError

  case class DuplicateTransfer(key: String, existingTx: Transaction)
      extends Exception(s"Duplicate idempotency key: $key")
      with TransferError

  def fromSession[F[_]: Concurrent](s: Session[F]): F[TransferService[F]] =
    (AccountRepo.fromSession(s), TransactionRepo.fromSession(s)).mapN { (accounts, txns) =>
      new TransferService[F] {

        def transfer(req: TransferRequest): F[Transaction] =
          // Idempotency check first (outside transaction)
          txns
            .findByIdempotencyKey(req.idempotencyKey)
            .flatMap {
              case Some(existing) =>
                MonadCancelThrow[F].raiseError(DuplicateTransfer(req.idempotencyKey, existing))
              case None =>
                doTransfer(req)
            }

        private def doTransfer(req: TransferRequest): F[Transaction] =
          // Serializable isolation for money movement
          s.transaction(TransactionIsolationLevel.Serializable, TransactionAccessMode.ReadWrite)
            .use { xa =>
              for {
                // Lock both accounts (ordered by UUID to prevent deadlocks)
                pair <- (if (req.fromAccountId.compareTo(req.toAccountId) < 0)
                           (req.fromAccountId, req.toAccountId)
                         else (req.toAccountId, req.fromAccountId)).pure[F]
                (srcId, dstId) = pair
                _             <- accounts.findByIdForUpdate(srcId)
                _             <- accounts.findByIdForUpdate(dstId)

                // Re-fetch in logical order
                src <- accounts
                         .findById(req.fromAccountId)
                         .flatMap(_.liftTo[F](AccountNotFound(req.fromAccountId)))
                dst <- accounts
                         .findById(req.toAccountId)
                         .flatMap(_.liftTo[F](AccountNotFound(req.toAccountId)))

                // Validations
                _ <- MonadCancelThrow[F]
                       .raiseError(AccountFrozen(src.id))
                       .whenA(src.status != AccountStatus.Active)
                _ <- MonadCancelThrow[F]
                       .raiseError(AccountFrozen(dst.id))
                       .whenA(dst.status != AccountStatus.Active)
                _ <- MonadCancelThrow[F]
                       .raiseError(CurrencyMismatch(req.currency, src.currency))
                       .whenA(src.currency != req.currency)
                _ <- MonadCancelThrow[F]
                       .raiseError(CurrencyMismatch(req.currency, dst.currency))
                       .whenA(dst.currency != req.currency)
                _ <- MonadCancelThrow[F]
                       .raiseError(InsufficientFunds(src.id, src.balance, req.amount))
                       .whenA(src.balance < req.amount)

                // Debit + Credit
                _ <- accounts.updateBalance(src.id, src.balance - req.amount)
                _ <- accounts.updateBalance(dst.id, dst.balance + req.amount)

                // Record the transaction
                tx <- txns.create(req, TxType.Transfer)
                _  <- txns.complete(tx.id)
              } yield tx
            }

      }
    }

}
