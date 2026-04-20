package kudi.service

import cats.effect.*
import cats.syntax.all.*

import kudi.domain.*
import kudi.repo.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.Session

trait WalletService[F[_]] {

  def createWallet(userId: Long, currencyCode: String, walletTypeId: WalletTypeId): F[Wallet]
  def getWallet(walletId: Long): F[Option[Wallet]]
  def getUserWallets(userId: Long): F[List[Wallet]]
  def getBalance(walletId: Long): F[Option[BigDecimal]]
  def freezeWallet(walletId: Long): F[Unit]
  def getStatement(walletId: Long, limit: Int): F[List[LedgerEntry]]

}

object WalletService {

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[WalletService[F]] =
    for {
      m       <- Instrumented.makeMetrics[F]("service.wallet")
      wallets <- WalletRepo.fromSession(s)
      ledger  <- LedgerRepo.fromSession(s)
      audit   <- AuditRepo.fromSession(s)
    } yield new WalletService[F] {

      def createWallet(userId: Long, currencyCode: String, walletTypeId: WalletTypeId): F[Wallet] =
        Instrumented.trace("WalletService.createWallet", m, Attribute("user.id", userId))(
          for {
            w <- wallets.create(userId, currencyCode, walletTypeId)
            _ <- audit.log(
                   actorId = Some(userId),
                   action = "wallet_created",
                   entityType = "wallet",
                   entityId = w.walletId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield w
        )

      def getWallet(walletId: Long): F[Option[Wallet]] =
        Instrumented.trace("WalletService.getWallet", m, Attribute("wallet.id", walletId))(
          wallets.findById(walletId)
        )

      def getUserWallets(userId: Long): F[List[Wallet]] =
        Instrumented.trace("WalletService.getUserWallets", m, Attribute("user.id", userId))(
          wallets.findByUser(userId)
        )

      def getBalance(walletId: Long): F[Option[BigDecimal]] =
        Instrumented.trace("WalletService.getBalance", m, Attribute("wallet.id", walletId))(
          wallets.findById(walletId).map(_.map(_.balance))
        )

      def freezeWallet(walletId: Long): F[Unit] =
        Instrumented.trace("WalletService.freezeWallet", m, Attribute("wallet.id", walletId))(
          for {
            _ <- wallets.freeze(walletId)
            _ <- audit.log(
                   actorId = None,
                   action = "wallet_frozen",
                   entityType = "wallet",
                   entityId = walletId.toString,
                   ipAddress = None,
                   oldValues = None,
                   newValues = None
                 )
          } yield ()
        )

      def getStatement(walletId: Long, limit: Int): F[List[LedgerEntry]] =
        Instrumented.trace("WalletService.getStatement", m, Attribute("wallet.id", walletId))(
          ledger.findByWallet(walletId, limit)
        )

    }

}
