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

trait LedgerRepo[F[_]] {

  def insertEntry(
    txnId: Long,
    txnCreatedAt: java.time.OffsetDateTime,
    walletId: Long,
    amount: BigDecimal,
    balanceAfter: BigDecimal,
    entryTypeId: EntryTypeId,
    currencyCode: String,
    description: Option[String]
  ): F[LedgerEntry]

  def findByWallet(walletId: Long, limit: Int): F[List[LedgerEntry]]
  def findByTxn(txnId: Long, txnCreatedAt: java.time.OffsetDateTime): F[List[LedgerEntry]]

}

object LedgerRepo {

  private val cols =
    "entry_id, txn_id, txn_created_at, wallet_id, amount, balance_after, created_at, entry_type_id, currency_code, description, metadata"

  private object SQL {

    val insert: Query[
      Long *: java.time.OffsetDateTime *: Long *: BigDecimal *: BigDecimal *: Short *: String *:
        Option[String] *: EmptyTuple,
      LedgerEntry
    ] =
      sql"""
        INSERT INTO ledger_entries (txn_id, txn_created_at, wallet_id, amount, balance_after, entry_type_id, currency_code, description)
        VALUES ($int8, $timestamptz, $int8, $numeric, $numeric, $int2, $bpchar(3), ${text.opt})
        RETURNING #$cols
      """.query(ledgerEntry)

    val findByWallet: Query[Long *: Int *: EmptyTuple, LedgerEntry] =
      sql"""
        SELECT #$cols FROM ledger_entries
        WHERE wallet_id = $int8
        ORDER BY created_at DESC
        LIMIT $int4
      """.query(ledgerEntry)

    val findByTxn: Query[Long *: java.time.OffsetDateTime *: EmptyTuple, LedgerEntry] =
      sql"""
        SELECT #$cols FROM ledger_entries
        WHERE txn_id = $int8 AND txn_created_at = $timestamptz
        ORDER BY entry_id
      """.query(ledgerEntry)

  }

  def fromSession[F[_]: Temporal: Tracer](s: Session[F])(using
    Meter[F]
  ): F[LedgerRepo[F]] =
    for {
      m         <- Instrumented.makeMetrics[F]("repo.ledger")
      pInsert   <- s.prepare(SQL.insert)
      pByWallet <- s.prepare(SQL.findByWallet)
      pByTxn    <- s.prepare(SQL.findByTxn)
    } yield new LedgerRepo[F] {

      def insertEntry(
        txnId: Long,
        txnCreatedAt: java.time.OffsetDateTime,
        walletId: Long,
        amount: BigDecimal,
        balanceAfter: BigDecimal,
        entryTypeId: EntryTypeId,
        currencyCode: String,
        description: Option[String]
      ): F[LedgerEntry] =
        Instrumented.trace(
          "LedgerRepo.insertEntry",
          m,
          Attribute("txn.id", txnId),
          Attribute("wallet.id", walletId)
        )(
          pInsert.unique(
            (
              txnId,
              txnCreatedAt,
              walletId,
              amount,
              balanceAfter,
              entryTypeId.value,
              currencyCode,
              description
            )
          )
        )

      def findByWallet(walletId: Long, limit: Int): F[List[LedgerEntry]] =
        Instrumented.trace("LedgerRepo.findByWallet", m, Attribute("wallet.id", walletId))(
          pByWallet.stream((walletId, limit), 64).compile.toList
        )

      def findByTxn(txnId: Long, txnCreatedAt: java.time.OffsetDateTime): F[List[LedgerEntry]] =
        Instrumented.trace("LedgerRepo.findByTxn", m, Attribute("txn.id", txnId))(
          pByTxn.stream((txnId, txnCreatedAt), 64).compile.toList
        )

    }

}
