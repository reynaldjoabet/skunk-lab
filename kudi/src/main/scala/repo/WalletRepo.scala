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

trait WalletRepo[F[_]] {

  def create(userId: Long, currencyCode: String, walletTypeId: WalletTypeId): F[Wallet]
  def findById(id: Long): F[Option[Wallet]]
  def findByIdForUpdate(id: Long): F[Option[Wallet]]
  def findByUser(userId: Long): F[List[Wallet]]
  def findByUserAndCurrency(userId: Long, currencyCode: String): F[Option[Wallet]]
  def debit(id: Long, amount: BigDecimal): F[Wallet]
  def credit(id: Long, amount: BigDecimal): F[Wallet]
  def freeze(id: Long): F[Unit]

}

object WalletRepo {

  private val cols =
    "wallet_id, user_id, balance, hold_amount, lifetime_in, lifetime_out, daily_limit, monthly_limit, created_at, wallet_type_id, status_id, is_default, currency_code, wallet_tag, metadata"

  private object SQL {

    val insert: Query[Long *: String *: Short *: EmptyTuple, Wallet] =
      sql"""
        INSERT INTO wallets (user_id, currency_code, wallet_type_id)
        VALUES ($int8, $bpchar(3), $int2)
        RETURNING #$cols
      """.query(wallet)

    val findById: Query[Long, Wallet] =
      sql"SELECT #$cols FROM wallets WHERE wallet_id = $int8".query(wallet)

    val findByIdForUpdate: Query[Long, Wallet] =
      sql"SELECT #$cols FROM wallets WHERE wallet_id = $int8 FOR UPDATE".query(wallet)

    val findByUser: Query[Long, Wallet] =
      sql"SELECT #$cols FROM wallets WHERE user_id = $int8 ORDER BY created_at".query(wallet)

    val findByUserAndCurrency: Query[Long *: String *: EmptyTuple, Wallet] =
      sql"SELECT #$cols FROM wallets WHERE user_id = $int8 AND currency_code = $bpchar(3)"
        .query(wallet)

    val debit =
      sql"""
        UPDATE wallets
        SET balance = balance - $numeric, lifetime_out = lifetime_out + $numeric
        WHERE wallet_id = $int8 AND balance >= $numeric AND status_id = 2
        RETURNING #$cols
      """.query(wallet)

    val credit =
      sql"""
        UPDATE wallets
        SET balance = balance + $numeric, lifetime_in = lifetime_in + $numeric
        WHERE wallet_id = $int8 AND status_id = 2
        RETURNING #$cols
      """.query(wallet)

    val freeze: Command[Long] =
      sql"UPDATE wallets SET status_id = 3 WHERE wallet_id = $int8".command

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[WalletRepo[F]] =
    for {
      m          <- Instrumented.makeMetrics[F]("repo.wallet")
      pInsert    <- s.prepare(SQL.insert)
      pFind      <- s.prepare(SQL.findById)
      pLock      <- s.prepare(SQL.findByIdForUpdate)
      pByUser    <- s.prepare(SQL.findByUser)
      pByUserCcy <- s.prepare(SQL.findByUserAndCurrency)
      pDebit     <- s.prepare(SQL.debit)
      pCredit    <- s.prepare(SQL.credit)
      pFreeze    <- s.prepare(SQL.freeze)
    } yield new WalletRepo[F] {

      def create(userId: Long, currencyCode: String, walletTypeId: WalletTypeId): F[Wallet] =
        Instrumented.trace("WalletRepo.create", m, Attribute("user.id", userId))(
          pInsert.unique((userId, currencyCode, walletTypeId.value))
        )

      def findById(id: Long): F[Option[Wallet]] =
        Instrumented.trace("WalletRepo.findById", m, Attribute("wallet.id", id))(
          pFind.option(id)
        )

      def findByIdForUpdate(id: Long): F[Option[Wallet]] =
        Instrumented.trace("WalletRepo.findByIdForUpdate", m, Attribute("wallet.id", id))(
          pLock.option(id)
        )

      def findByUser(userId: Long): F[List[Wallet]] =
        Instrumented.trace("WalletRepo.findByUser", m, Attribute("user.id", userId))(
          pByUser.stream(userId, 64).compile.toList
        )

      def findByUserAndCurrency(userId: Long, currencyCode: String): F[Option[Wallet]] =
        Instrumented.trace("WalletRepo.findByUserAndCurrency", m, Attribute("user.id", userId))(
          pByUserCcy.option((userId, currencyCode))
        )

      def debit(id: Long, amount: BigDecimal): F[Wallet] =
        Instrumented.trace("WalletRepo.debit", m, Attribute("wallet.id", id))(
          pDebit
            .option((amount, amount, id, amount))
            .flatMap {
              case Some(w) => w.pure[F]
              case None =>
                MonadCancelThrow[F].raiseError(
                  new Exception(s"Debit failed: wallet $id insufficient funds or not active")
                )
            }
        )

      def credit(id: Long, amount: BigDecimal): F[Wallet] =
        Instrumented.trace("WalletRepo.credit", m, Attribute("wallet.id", id))(
          pCredit
            .option((amount, amount, id))
            .flatMap {
              case Some(w) => w.pure[F]
              case None =>
                MonadCancelThrow[F]
                  .raiseError(new Exception(s"Credit failed: wallet $id not active"))
            }
        )

      def freeze(id: Long): F[Unit] =
        Instrumented.trace("WalletRepo.freeze", m, Attribute("wallet.id", id))(
          pFreeze.execute(id).void
        )

    }

}
