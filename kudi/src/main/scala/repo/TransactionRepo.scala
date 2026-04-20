package kudi.repo

import cats.effect.*
import cats.syntax.all.*

import kudi.db.Codecs.*
import kudi.domain.*
import kudi.Instrumented
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute
import skunk.{Transaction as _, *}
import skunk.codec.all.*
import skunk.implicits.*

trait TransactionRepo[F[_]] {

  def create(
    sourceWalletId: Option[Long],
    destWalletId: Option[Long],
    amount: BigDecimal,
    feeAmount: BigDecimal,
    txnTypeId: TxnTypeId,
    sourceCurrency: Option[String],
    destCurrency: Option[String],
    referenceId: String,
    description: Option[String],
    ipAddress: Option[String]
  ): F[Transaction]

  def complete(txnId: Long, createdAt: java.time.OffsetDateTime): F[Unit]
  def fail(txnId: Long, createdAt: java.time.OffsetDateTime, reason: String): F[Unit]
  def findByReference(referenceId: String): F[Option[Transaction]]
  def findByWallet(walletId: Long, limit: Int): F[List[Transaction]]

}

object TransactionRepo {

  private val cols =
    "txn_id, source_wallet_id, dest_wallet_id, amount, fee_amount, exchange_rate, balance_after, created_at, completed_at, expires_at, merchant_id, related_txn_id, txn_type_id, status_id, is_suspicious, source_currency, dest_currency, reference_id, description, failure_reason, ip_address::text, device_fingerprint, metadata"

  private object SQL {

    val insert: Query[
      Option[Long] *: Option[Long] *: BigDecimal *: BigDecimal *: Short *: Option[String] *:
        Option[String] *: String *: Option[String] *:
        Option[
          String
        ] *: EmptyTuple,
      Transaction
    ] =
      sql"""
        INSERT INTO transactions (
          source_wallet_id, dest_wallet_id, amount, fee_amount, txn_type_id,
          source_currency, dest_currency, reference_id, description, ip_address
        ) VALUES (
          ${int8.opt}, ${int8.opt}, $numeric, $numeric, $int2,
          ${bpchar(3).opt}, ${bpchar(3).opt}, $text, ${text.opt}, ${text.opt}::inet
        )
        RETURNING #$cols
      """.query(transaction)

    val complete: Command[Long *: java.time.OffsetDateTime *: EmptyTuple] =
      sql"""
        UPDATE transactions SET status_id = 3, completed_at = now()
        WHERE txn_id = $int8 AND created_at = $timestamptz
      """.command

    val fail: Command[String *: Long *: java.time.OffsetDateTime *: EmptyTuple] =
      sql"""
        UPDATE transactions SET status_id = 4, failure_reason = $text
        WHERE txn_id = $int8 AND created_at = $timestamptz
      """.command

    val findByReference: Query[String, Transaction] =
      sql"SELECT #$cols FROM transactions WHERE reference_id = $text".query(transaction)

    val findByWallet: Query[Long *: Long *: Int *: EmptyTuple, Transaction] =
      sql"""
        SELECT #$cols FROM transactions
        WHERE source_wallet_id = $int8 OR dest_wallet_id = $int8
        ORDER BY created_at DESC
        LIMIT $int4
      """.query(transaction)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[TransactionRepo[F]] =
    for {
      m             <- Instrumented.makeMetrics[F]("repo.transaction")
      pInsert       <- s.prepare(SQL.insert)
      pComplete     <- s.prepare(SQL.complete)
      pFail         <- s.prepare(SQL.fail)
      pFindByRef    <- s.prepare(SQL.findByReference)
      pFindByWallet <- s.prepare(SQL.findByWallet)
    } yield new TransactionRepo[F] {

      def create(
        sourceWalletId: Option[Long],
        destWalletId: Option[Long],
        amount: BigDecimal,
        feeAmount: BigDecimal,
        txnTypeId: TxnTypeId,
        sourceCurrency: Option[String],
        destCurrency: Option[String],
        referenceId: String,
        description: Option[String],
        ipAddress: Option[String]
      ): F[Transaction] =
        Instrumented.trace("TransactionRepo.create", m, Attribute("txn.reference", referenceId))(
          pInsert.unique(
            (
              sourceWalletId,
              destWalletId,
              amount,
              feeAmount,
              txnTypeId.value,
              sourceCurrency,
              destCurrency,
              referenceId,
              description,
              ipAddress
            )
          )
        )

      def complete(txnId: Long, createdAt: java.time.OffsetDateTime): F[Unit] =
        Instrumented.trace("TransactionRepo.complete", m, Attribute("txn.id", txnId))(
          pComplete.execute((txnId, createdAt)).void
        )

      def fail(txnId: Long, createdAt: java.time.OffsetDateTime, reason: String): F[Unit] =
        Instrumented.trace("TransactionRepo.fail", m, Attribute("txn.id", txnId))(
          pFail.execute((reason, txnId, createdAt)).void
        )

      def findByReference(referenceId: String): F[Option[Transaction]] =
        Instrumented
          .trace("TransactionRepo.findByReference", m, Attribute("txn.reference", referenceId))(
            pFindByRef.option(referenceId)
          )

      def findByWallet(walletId: Long, limit: Int): F[List[kudi.domain.Transaction]] =
        Instrumented.trace("TransactionRepo.findByWallet", m, Attribute("wallet.id", walletId))(
          pFindByWallet.stream((walletId, walletId, limit), 64).compile.toList
        )

    }

}
