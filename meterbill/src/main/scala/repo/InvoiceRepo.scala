package meterbill.repo

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.db.Codecs._
import meterbill.domain._
import skunk._
import skunk.codec.all._
import skunk.implicits._

trait InvoiceRepo[F[_]] {

  def create(
    tenantId: UUID,
    subId: UUID,
    periodStart: OffsetDateTime,
    periodEnd: OffsetDateTime,
    baseAmount: Long,
    overageUnits: Long,
    overageAmount: Long,
    totalAmount: Long
  ): F[Invoice]

  def addLineItem(
    invoiceId: UUID,
    desc: String,
    qty: Long,
    unitPrice: Long,
    amount: Long
  ): F[LineItem]

  def findById(id: UUID): F[Option[Invoice]]
  def findByTenant(tenantId: UUID): F[List[Invoice]]
  def getLineItems(invoiceId: UUID): F[List[LineItem]]
  def issue(id: UUID): F[Unit]
  def markPaid(id: UUID): F[Unit]
  def markVoid(id: UUID): F[Unit]

}

object InvoiceRepo {

  private val invCols =
    "id, tenant_id, subscription_id, period_start, period_end, base_amount, overage_units, overage_amount, total_amount, status, issued_at, paid_at, created_at"

  private val liCols = "id, invoice_id, description, quantity, unit_price_cents, amount_cents"

  private object SQL {

    val insertInvoice: Query[
      UUID *: UUID *: OffsetDateTime *: OffsetDateTime *: Long *: Long *: Long *: Long *:
        EmptyTuple,
      Invoice
    ] =
      sql"""
        INSERT INTO invoices (tenant_id, subscription_id, period_start, period_end, base_amount, overage_units, overage_amount, total_amount)
        VALUES ($uuid, $uuid, $timestamptz, $timestamptz, $int8, $int8, $int8, $int8)
        RETURNING #$invCols
      """.query(invoice)

    val insertLineItem: Query[UUID *: String *: Long *: Long *: Long *: EmptyTuple, LineItem] =
      sql"""
        INSERT INTO line_items (invoice_id, description, quantity, unit_price_cents, amount_cents)
        VALUES ($uuid, $text, $int8, $int8, $int8)
        RETURNING #$liCols
      """.query(lineItem)

    val byId: Query[UUID, Invoice] =
      sql"SELECT #$invCols FROM invoices WHERE id = $uuid".query(invoice)

    val byTenant: Query[UUID, Invoice] =
      sql"SELECT #$invCols FROM invoices WHERE tenant_id = $uuid ORDER BY created_at DESC"
        .query(invoice)

    val lineItemsByInvoice: Query[UUID, LineItem] =
      sql"SELECT #$liCols FROM line_items WHERE invoice_id = $uuid ORDER BY id".query(lineItem)

    val doIssue: Command[UUID] =
      sql"UPDATE invoices SET status = 'open', issued_at = now() WHERE id = $uuid".command

    val doPaid: Command[UUID] =
      sql"UPDATE invoices SET status = 'paid', paid_at = now() WHERE id = $uuid".command

    val doVoid: Command[UUID] =
      sql"UPDATE invoices SET status = 'void' WHERE id = $uuid".command

  }

  def make[F[_]: Concurrent](s: Session[F]): F[InvoiceRepo[F]] =
    (
      s.prepare(SQL.insertInvoice),
      s.prepare(SQL.insertLineItem),
      s.prepare(SQL.byId),
      s.prepare(SQL.byTenant),
      s.prepare(SQL.lineItemsByInvoice),
      s.prepare(SQL.doIssue),
      s.prepare(SQL.doPaid),
      s.prepare(SQL.doVoid)
    ).mapN { (pInsert, pLi, pById, pByTenant, pLineItems, pIssue, pPaid, pVoid) =>
      new InvoiceRepo[F] {

        def create(
          tid: UUID,
          sid: UUID,
          ps: OffsetDateTime,
          pe: OffsetDateTime,
          base: Long,
          ou: Long,
          oa: Long,
          total: Long
        ) =
          pInsert.unique((tid, sid, ps, pe, base, ou, oa, total))

        def addLineItem(iid: UUID, desc: String, qty: Long, up: Long, amt: Long) =
          pLi.unique((iid, desc, qty, up, amt))

        def findById(id: UUID)      = pById.option(id)
        def findByTenant(tid: UUID) = pByTenant.stream(tid, 32).compile.toList
        def getLineItems(iid: UUID) = pLineItems.stream(iid, 64).compile.toList
        def issue(id: UUID)         = pIssue.execute(id).void
        def markPaid(id: UUID)      = pPaid.execute(id).void
        def markVoid(id: UUID)      = pVoid.execute(id).void

      }
    }

}
