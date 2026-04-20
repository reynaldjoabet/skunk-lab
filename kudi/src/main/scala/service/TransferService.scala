package kudi.service

import cats.effect.*
import cats.syntax.all.*

import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait TransferService[F[_]] {

  def transfer(req: TransferRequest): F[Transaction]
  def topUp(req: TopUpRequest): F[Transaction]
  def withdraw(req: WithdrawalRequest): F[Transaction]

}

object TransferService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    m: Meter[F]
  ): F[TransferService[F]] =
    for {
      met <- Instrumented.makeMetrics[F]("service.transfer")
      txnCount <- m.counter[Long]("service.transfer.completed")
                    .withUnit("{transactions}")
                    .withDescription("Completed transfer count by type")
                    .create
      wallets <- WalletRepo.fromSession(s)
      txns    <- TransactionRepo.fromSession(s)
      ledger  <- LedgerRepo.fromSession(s)
      audit   <- AuditRepo.fromSession(s)
    } yield new TransferService[F] {

      private def countTxn(txnType: String): F[Unit] =
        txnCount.add(1L, Attribute("txn.type", txnType))

      def transfer(req: TransferRequest): F[Transaction] =
        Instrumented.trace(
          "TransferService.transfer",
          met,
          Attribute("txn.reference", req.referenceId),
          Attribute("txn.amount", req.amount.toDouble)
        ) {
          for {
            existing <- txns.findByReference(req.referenceId)
            txn <- existing match {
                     case Some(t) => t.pure[F]
                     case None    => doTransfer(req)
                   }
          } yield txn
        }

      private def doTransfer(req: TransferRequest): F[Transaction] = {
        val (firstId, secondId) =
          if (req.sourceWalletId < req.destWalletId) (req.sourceWalletId, req.destWalletId)
          else (req.destWalletId, req.sourceWalletId)

        for {
          _ <- wallets.findByIdForUpdate(firstId)
          _ <- wallets.findByIdForUpdate(secondId)

          txn <- txns.create(
                   sourceWalletId = Some(req.sourceWalletId),
                   destWalletId = Some(req.destWalletId),
                   amount = req.amount,
                   feeAmount = BigDecimal(0),
                   txnTypeId = TxnTypeId.P2pSend,
                   sourceCurrency = Some(req.currencyCode),
                   destCurrency = Some(req.currencyCode),
                   referenceId = req.referenceId,
                   description = req.description,
                   ipAddress = None
                 )

          debited  <- wallets.debit(req.sourceWalletId, req.amount)
          credited <- wallets.credit(req.destWalletId, req.amount)

          _ <- ledger.insertEntry(
                 txnId = txn.txnId,
                 txnCreatedAt = txn.createdAt,
                 walletId = req.sourceWalletId,
                 amount = -req.amount,
                 balanceAfter = debited.balance,
                 entryTypeId = EntryTypeId.Debit,
                 currencyCode = req.currencyCode,
                 description = req.description
               )
          _ <- ledger.insertEntry(
                 txnId = txn.txnId,
                 txnCreatedAt = txn.createdAt,
                 walletId = req.destWalletId,
                 amount = req.amount,
                 balanceAfter = credited.balance,
                 entryTypeId = EntryTypeId.Credit,
                 currencyCode = req.currencyCode,
                 description = req.description
               )

          _ <- txns.complete(txn.txnId, txn.createdAt)
          _ <- countTxn("p2p_transfer")

          _ <- audit.log(
                 actorId = None,
                 action = "p2p_transfer",
                 entityType = "transaction",
                 entityId = txn.txnId.toString,
                 ipAddress = None,
                 oldValues = None,
                 newValues = None
               )
        } yield txn
      }

      def topUp(req: TopUpRequest): F[Transaction] =
        Instrumented.trace(
          "TransferService.topUp",
          met,
          Attribute("txn.reference", req.referenceId),
          Attribute("txn.amount", req.amount.toDouble)
        ) {
          for {
            existing <- txns.findByReference(req.referenceId)
            txn <- existing match {
                     case Some(t) => t.pure[F]
                     case None =>
                       for {
                         _ <- wallets.findByIdForUpdate(req.destWalletId)
                         txn <- txns.create(
                                  sourceWalletId = None,
                                  destWalletId = Some(req.destWalletId),
                                  amount = req.amount,
                                  feeAmount = BigDecimal(0),
                                  txnTypeId = TxnTypeId.TopUp,
                                  sourceCurrency = None,
                                  destCurrency = Some(req.currencyCode),
                                  referenceId = req.referenceId,
                                  description = req.description,
                                  ipAddress = None
                                )
                         credited <- wallets.credit(req.destWalletId, req.amount)
                         _ <- ledger.insertEntry(
                                txnId = txn.txnId,
                                txnCreatedAt = txn.createdAt,
                                walletId = req.destWalletId,
                                amount = req.amount,
                                balanceAfter = credited.balance,
                                entryTypeId = EntryTypeId.Credit,
                                currencyCode = req.currencyCode,
                                description = req.description
                              )
                         _ <- txns.complete(txn.txnId, txn.createdAt)
                         _ <- countTxn("top_up")
                       } yield txn
                   }
          } yield txn
        }

      def withdraw(req: WithdrawalRequest): F[Transaction] =
        Instrumented.trace(
          "TransferService.withdraw",
          met,
          Attribute("txn.reference", req.referenceId),
          Attribute("txn.amount", req.amount.toDouble)
        ) {
          for {
            existing <- txns.findByReference(req.referenceId)
            txn <- existing match {
                     case Some(t) => t.pure[F]
                     case None =>
                       for {
                         _ <- wallets.findByIdForUpdate(req.sourceWalletId)
                         txn <- txns.create(
                                  sourceWalletId = Some(req.sourceWalletId),
                                  destWalletId = None,
                                  amount = req.amount,
                                  feeAmount = BigDecimal(0),
                                  txnTypeId = TxnTypeId.Withdrawal,
                                  sourceCurrency = Some(req.currencyCode),
                                  destCurrency = None,
                                  referenceId = req.referenceId,
                                  description = req.description,
                                  ipAddress = None
                                )
                         debited <- wallets.debit(req.sourceWalletId, req.amount)
                         _ <- ledger.insertEntry(
                                txnId = txn.txnId,
                                txnCreatedAt = txn.createdAt,
                                walletId = req.sourceWalletId,
                                amount = -req.amount,
                                balanceAfter = debited.balance,
                                entryTypeId = EntryTypeId.Debit,
                                currencyCode = req.currencyCode,
                                description = req.description
                              )
                         _ <- txns.complete(txn.txnId, txn.createdAt)
                         _ <- countTxn("withdrawal")
                       } yield txn
                   }
          } yield txn
        }

    }

}
