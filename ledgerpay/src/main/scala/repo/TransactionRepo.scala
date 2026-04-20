package ledgerpay.repo

import java.util.UUID

import cats.effect._
import cats.syntax.all._
import fs2.Stream

import ledgerpay.db.Codecs._
import ledgerpay.domain._
import org.typelevel.otel4s.context.syntax.toContextSyntax
import skunk.{Transaction as _, *}
import skunk.codec.all._
import skunk.implicits._

trait TransactionRepo[F[_]] {

  def create(tx: TransferRequest, txType: TxType): F[Transaction]
  def complete(id: UUID): F[Unit]
  def fail(id: UUID): F[Unit]
  def findById(id: UUID): F[Option[Transaction]]
  def findByAccount(accountId: UUID): Stream[F, Transaction]
  def findByIdempotencyKey(key: String): F[Option[Transaction]]

}

object TransactionRepo {

  private val txCols =
    "id, idempotency_key, tx_type, status, source_acct_id, dest_acct_id, amount, currency, description, created_at, completed_at"

  private object SQL {

    val insert: Query[
      String *: TxType *: UUID *: UUID *: BigDecimal *: CurrencyCode *:
        Option[
          String
        ] *: EmptyTuple,
      Transaction
    ] =
      sql"""
        INSERT INTO transactions (idempotency_key, tx_type, source_acct_id, dest_acct_id, amount, currency, description)
        VALUES ($varchar, $txType, $uuid, $uuid, $numeric, $currencyCode, ${text.opt})
        RETURNING #$txCols
      """.query(transaction)

    val findById: Query[UUID, Transaction] =
      sql"SELECT #$txCols FROM transactions WHERE id = $uuid".query(transaction)

    val findByIdempotencyKey: Query[String, Transaction] =
      sql"SELECT #$txCols FROM transactions WHERE idempotency_key = $varchar".query(transaction)

    val findByAccount: Query[UUID, Transaction] =
      sql"""
        SELECT #$txCols FROM transactions
        WHERE source_acct_id = $uuid OR dest_acct_id = $uuid
        ORDER BY created_at DESC
      """
        .query(transaction)
        // Note: skunk deduplicates $uuid references — we need to pass it twice
        .contramap[UUID](id => (id, id))

    val complete: Command[UUID] =
      sql"UPDATE transactions SET status = 'completed', completed_at = now() WHERE id = $uuid"
        .command

    val fail: Command[UUID] =
      sql"UPDATE transactions SET status = 'failed' WHERE id = $uuid".command

  }

  def fromSession[F[_]: Concurrent](s: Session[F]): F[TransactionRepo[F]] =
    (
      s.prepare(SQL.insert),
      s.prepare(SQL.findById),
      s.prepare(SQL.findByIdempotencyKey),
      s.prepare(SQL.findByAccount),
      s.prepare(SQL.complete),
      s.prepare(SQL.fail)
    ).mapN { (pInsert, pFind, pFindByKey, pFindByAcct, pComplete, pFail) =>
      new TransactionRepo[F] {

        def create(req: TransferRequest, txType: TxType): F[Transaction] =
          pInsert.unique(
            (
              req.idempotencyKey,
              txType,
              req.fromAccountId,
              req.toAccountId,
              req.amount,
              req.currency,
              req.description
            )
          )

        def complete(id: UUID): F[Unit] =
          pComplete.execute(id).void

        def fail(id: UUID): F[Unit] =
          pFail.execute(id).void

        def findById(id: UUID): F[Option[Transaction]] =
          pFind.option(id)

        def findByAccount(accountId: UUID): Stream[F, Transaction] =
          pFindByAcct.stream(accountId, 64)

        def findByIdempotencyKey(key: String): F[Option[Transaction]] =
          pFindByKey.option(key)

      }
    }

}
