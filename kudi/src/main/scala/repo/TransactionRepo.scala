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

  /**
    * Claims an idempotency key for the current database transaction. Returns None when the caller
    * now owns the key and must create the transaction (create() records it against the key).
    * Returns the original transaction when the key was used before; a concurrent request holding
    * the key is waited for, so the money is never moved twice.
    *
    * Must run inside the same database transaction as the create() that follows.
    */
  def claimReference(referenceId: String): F[Option[Transaction]]
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
      // Inserts the transaction and links it to its claimed key in one round trip.
      sql"""
        WITH t AS (
          INSERT INTO transactions (
            source_wallet_id, dest_wallet_id, amount, fee_amount, txn_type_id,
            source_currency, dest_currency, reference_id, description, ip_address
          ) VALUES (
            ${int8.opt}, ${int8.opt}, $numeric, $numeric, $int2,
            ${text.opt}, ${text.opt}, $text, ${text.opt}, ${text.opt}::inet
          )
          RETURNING #$cols
        ), linked AS (
          UPDATE transaction_references r
          SET txn_id = t.txn_id, txn_created_at = t.created_at
          FROM t
          WHERE r.reference_id = t.reference_id
        )
        SELECT * FROM t
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

    // transaction_references holds the partition key, so the lookup touches one partition.
    val findByReference: Query[String, Transaction] =
      sql"""
        SELECT #$cols FROM transactions
        WHERE (txn_id, created_at) = (
          SELECT txn_id, txn_created_at FROM transaction_references WHERE reference_id = $text
        )
      """.query(transaction)

    // PostgreSQL 19: one statement either claims the key (old row is NULL) or
    // returns the existing claim, locked until this transaction ends. A
    // concurrent claimant blocks here until the first one commits or rolls back.
    val claim
        : Query[String, Boolean *: Option[Long] *: Option[java.time.OffsetDateTime] *: EmptyTuple] =
      sql"""
        INSERT INTO transaction_references (reference_id) VALUES ($text)
        ON CONFLICT (reference_id) DO SELECT FOR UPDATE
        RETURNING old.reference_id IS NULL, txn_id, txn_created_at
      """.query(bool *: int8.opt *: timestamptz.opt)

    val byIdAndCreatedAt: Query[Long *: java.time.OffsetDateTime *: EmptyTuple, Transaction] =
      sql"SELECT #$cols FROM transactions WHERE txn_id = $int8 AND created_at = $timestamptz"
        .query(transaction)

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
      pClaim        <- s.prepare(SQL.claim)
      pById         <- s.prepare(SQL.byIdAndCreatedAt)
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
        Instrumented.trace(
          "TransactionRepo.findByReference",
          m,
          Attribute("txn.reference", referenceId)
        )(
          pFindByRef.option(referenceId)
        )

      def claimReference(referenceId: String): F[Option[Transaction]] =
        Instrumented.trace(
          "TransactionRepo.claimReference",
          m,
          Attribute("txn.reference", referenceId)
        )(
          pClaim.unique(referenceId).flatMap {
            case (true, _, _)                       => none[Transaction].pure[F]
            case (false, Some(id), Some(createdAt)) => pById.option((id, createdAt))
            case (false, _, _)                      =>
              // Unreachable while every claim is recorded in the same transaction as its create().
              MonadCancelThrow[F].raiseError(
                new IllegalStateException(
                  s"Reference $referenceId is claimed but has no transaction"
                )
              )
          }
        )

      def findByWallet(walletId: Long, limit: Int): F[List[kudi.domain.Transaction]] =
        Instrumented.trace("TransactionRepo.findByWallet", m, Attribute("wallet.id", walletId))(
          pFindByWallet.stream((walletId, walletId, limit), 64).compile.toList
        )

    }

}
