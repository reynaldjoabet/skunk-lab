package ledgerpay.service

import java.util.UUID

import cats.effect.*
import cats.syntax.all.*

import io.circe.syntax.*
import io.circe.Json
import ledgerpay.domain.*
import ledgerpay.repo.*
import ledgerpay.service.NotificationService
import ledgerpay.service.TransferService.*
import skunk.*
import skunk.data.TransactionAccessMode
import skunk.data.TransactionIsolationLevel

object TransferServiceV2 {

  def fromSession[F[_]: MonadCancelThrow: Concurrent](
    s: Session[F],
    notifications: NotificationService[F]
  ): F[TransferService[F]] =
    (
      AccountRepo.fromSession(s),
      TransactionRepo.fromSession(s),
      VelocityService.fromSession(s),
      AuditRepo.fromSession(s)
    ).mapN { (accounts, txns, velocity, audit) =>
      new TransferService[F] {

        def transfer(req: TransferRequest): F[ledgerpay.domain.Transaction] =
          // Idempotency check
          txns
            .findByIdempotencyKey(req.idempotencyKey)
            .flatMap {
              case Some(existing) =>
                MonadCancelThrow[F].raiseError(DuplicateTransfer(req.idempotencyKey, existing))
              case None =>
                doTransfer(req)
            }

        private def doTransfer(req: TransferRequest): F[ledgerpay.domain.Transaction] = {
          s.transaction(TransactionIsolationLevel.Serializable, TransactionAccessMode.ReadWrite)
            .use { xa =>
              // Lock in deterministic order
              val first =
                if (req.fromAccountId.compareTo(req.toAccountId) < 0) req.fromAccountId
                else req.toAccountId
              val second =
                if (req.fromAccountId.compareTo(req.toAccountId) < 0) req.toAccountId
                else req.fromAccountId
              for {
                _ <- accounts.findByIdForUpdate(first)
                _ <- accounts.findByIdForUpdate(second)

                src <- accounts
                         .findById(req.fromAccountId)
                         .flatMap(_.liftTo[F](AccountNotFound(req.fromAccountId)))
                dst <- accounts
                         .findById(req.toAccountId)
                         .flatMap(_.liftTo[F](AccountNotFound(req.toAccountId)))

                // Validate
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
                       .raiseError(InsufficientFunds(src.id, src.balance, req.amount))
                       .whenA(src.balance < req.amount)

                // Velocity check
                check <- velocity.checkDailyLimit(src.id, req.amount)
                _ <-
                  check
                    .leftTraverse(msg => MonadCancelThrow[F].raiseError[Unit](new Exception(msg)))

                // Execute
                _  <- accounts.updateBalance(src.id, src.balance - req.amount)
                _  <- accounts.updateBalance(dst.id, dst.balance + req.amount)
                tx <- txns.create(req, TxType.Transfer)
                _  <- txns.complete(tx.id)

                // Audit (inside the transaction — committed atomically)
                _ <- audit.log(
                       "transaction",
                       tx.id,
                       "transfer_completed",
                       None,
                       Some(
                         Json.obj(
                           "from"     -> src.id.toString.asJson,
                           "to"       -> dst.id.toString.asJson,
                           "amount"   -> req.amount.toString.asJson,
                           "currency" -> req.currency.label.asJson
                         )
                       )
                     )
              } yield tx
            }
            .flatTap { tx =>
              // Notifications OUTSIDE the transaction (fire-and-forget after commit)
              val srcEvent =
                BalanceChangeEvent(req.fromAccountId, BigDecimal(0) /*refetch if needed*/, tx.id)
              val dstEvent = BalanceChangeEvent(req.toAccountId, BigDecimal(0), tx.id)
              notifications.publishBalanceChange(srcEvent) *>
                notifications.publishBalanceChange(dstEvent)
            }
        }

      }
    }

}
