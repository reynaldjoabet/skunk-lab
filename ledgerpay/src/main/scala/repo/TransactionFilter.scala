package ledgerpay.repo

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect._
import cats.implicits.catsSyntaxTuple2Semigroupal
import cats.syntax.all._

import ledgerpay.db.Codecs._
import ledgerpay.domain._
import skunk._
import skunk.circe.codec.all.jsonb
import skunk.codec.all._
import skunk.implicits._

case class TransactionFilter(
  accountId: Option[UUID] = None,
  txType: Option[TxType] = None,
  status: Option[TxStatus] = None,
  currency: Option[CurrencyCode] = None,
  minAmount: Option[BigDecimal] = None,
  maxAmount: Option[BigDecimal] = None,
  after: Option[OffsetDateTime] = None,
  before: Option[OffsetDateTime] = None,
  limit: Int = 50,
  offset: Int = 0
)

trait TransactionSearchRepo[F[_]] {
  def search(filter: TransactionFilter): F[List[ledgerpay.domain.Transaction]]
}

object TransactionSearchRepo {

  private val txCols =
    "id, idempotency_key, tx_type, status, source_acct_id, dest_acct_id, amount, currency, description, created_at, completed_at"

  def fromSession[F[_]: Concurrent](s: Session[F]): TransactionSearchRepo[F] =
    new TransactionSearchRepo[F] {

      def search(f: TransactionFilter): F[List[ledgerpay.domain.Transaction]] = {
        val base = sql"SELECT #$txCols FROM transactions"

        // Each filter becomes an optional AppliedFragment
        val conds: List[AppliedFragment] = List(
          f.accountId.map(id => sql"(source_acct_id = $uuid OR dest_acct_id = $uuid)" ((id, id))),
          f.txType.map(t => sql"tx_type = $txType" (t)),
          f.status.map(st => sql"status = $txStatus" (st)),
          f.currency.map(c => sql"currency = $currencyCode" (c)),
          f.minAmount.map(min => sql"amount >= $numeric" (min)),
          f.maxAmount.map(max => sql"amount <= $numeric" (max)),
          f.after.map(a => sql"created_at >= $timestamptz" (a)),
          f.before.map(b => sql"created_at <= $timestamptz" (b))
        ).flatten

        val where =
          if (conds.isEmpty) AppliedFragment.empty
          else conds.foldSmash(void" WHERE ", void" AND ", AppliedFragment.empty)

        val order  = void" ORDER BY created_at DESC"
        val paging = sql" LIMIT $int4 OFFSET $int4" ((f.limit, f.offset))

        val af = base(Void) |+| where |+| order |+| paging

        s.prepare(af.fragment.query(transaction)).flatMap(_.stream(af.argument, 64).compile.toList)
      }

    }

}
