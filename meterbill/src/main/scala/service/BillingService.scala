package meterbill.service

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect._
import cats.syntax.all._

import meterbill.domain._
import meterbill.repo._
import skunk.Session

trait BillingService[F[_]] {

  def preview(subscriptionId: UUID): F[InvoicePreview]
  def generate(subscriptionId: UUID): F[Invoice]
  def markPaid(invoiceId: UUID): F[Unit]
  def listInvoices(tenantId: UUID): F[List[Invoice]]
  def getInvoiceWithLines(invoiceId: UUID): F[Option[(Invoice, List[LineItem])]]

}

object BillingService {

  case class SubNotFound(id: UUID)     extends Exception(s"Subscription $id not found")
  case class PlanNotFound(id: UUID)    extends Exception(s"Plan $id not found")
  case class InvoiceNotFound(id: UUID) extends Exception(s"Invoice $id not found")
  case class AlreadyInvoiced()         extends Exception("Period already invoiced")

  case class PeriodNotEnded(end: OffsetDateTime)
      extends Exception(s"Current period is still open; it can be invoiced from $end")

  case class InvoiceNotPayable(id: UUID, status: InvoiceStatus)
      extends Exception(s"Invoice $id is ${status.label} and cannot be paid")

  def make[F[_]: Concurrent: Clock](s: Session[F]): F[BillingService[F]] =
    (SubscriptionRepo.make(s), PlanRepo.make(s), UsageRepo.make(s), InvoiceRepo.make(s)).mapN {
      (subs, plans, usage, invoices) =>
        new BillingService[F] {

          private def calculate(sub: Subscription, plan: Plan): F[InvoicePreview] =
            usage
              .summaryForPeriod(sub.id, sub.currentPeriodStart, sub.currentPeriodEnd)
              .map(InvoiceCalculator.price(plan, _))

          def preview(subscriptionId: UUID): F[InvoicePreview] =
            for {
              sub  <- subs.findById(subscriptionId).flatMap(_.liftTo[F](SubNotFound(subscriptionId)))
              plan <- plans.findById(sub.planId).flatMap(_.liftTo[F](PlanNotFound(sub.planId)))
              prev <- calculate(sub, plan)
            } yield prev

          def generate(subscriptionId: UUID): F[Invoice] =
            s.transaction
              .use { _ =>
                for {
                  sub <-
                    subs.findById(subscriptionId).flatMap(_.liftTo[F](SubNotFound(subscriptionId)))
                  plan <- plans.findById(sub.planId).flatMap(_.liftTo[F](PlanNotFound(sub.planId)))
                  // Only a closed period can be invoiced. Invoicing early would
                  // advance the period, and usage recorded in the rest of the
                  // old period would then never be billed.
                  now <- Clock[F].realTimeInstant
                  _   <- MonadCancelThrow[F]
                         .raiseError(PeriodNotEnded(sub.currentPeriodEnd))
                         .whenA(now.isBefore(sub.currentPeriodEnd.toInstant))
                  prev    <- calculate(sub, plan)
                  invoice <- invoices
                               .create(
                                 sub.tenantId,
                                 sub.id,
                                 sub.currentPeriodStart,
                                 sub.currentPeriodEnd,
                                 prev.baseAmount,
                                 prev.overageUnits,
                                 prev.overageAmount,
                                 prev.totalAmount
                               )
                               .recoverWith { case skunk.SqlState.UniqueViolation(_) =>
                                 MonadCancelThrow[F].raiseError(AlreadyInvoiced())
                               }
                  // Write line items
                  _ <- prev.lineItems
                         .traverse_ { li =>
                           invoices.addLineItem(
                             invoice.id,
                             li.description,
                             li.quantity,
                             li.unitPriceCents,
                             li.amountCents
                           )
                         }
                  _ <- invoices.issue(invoice.id)
                  // Advance subscription period
                  _ <- subs.advancePeriod(
                         sub.id,
                         sub.currentPeriodEnd,
                         InvoiceCalculator.periodEnd(plan.period, sub.currentPeriodEnd)
                       )
                  // Re-read so the response shows the issued invoice (status
                  // open, issued_at set) rather than the draft row as inserted.
                  issued <- invoices.findById(invoice.id)
                } yield issued.getOrElse(invoice)
              }

          def markPaid(invoiceId: UUID): F[Unit] =
            // The update only matches an open invoice, so the status check and
            // the write are one atomic statement. If nothing matched, look at
            // why: paying an already-paid invoice is a harmless retry, anything
            // else (draft, void, uncollectible, missing) is an error.
            invoices
              .markPaid(invoiceId)
              .ifM(
                ().pure[F],
                invoices
                  .findById(invoiceId)
                  .flatMap {
                    case None                                          => InvoiceNotFound(invoiceId).raiseError[F, Unit]
                    case Some(inv) if inv.status == InvoiceStatus.Paid => ().pure[F]
                    case Some(inv)                                     =>
                      InvoiceNotPayable(invoiceId, inv.status).raiseError[F, Unit]
                  }
              )

          def listInvoices(tenantId: UUID): F[List[Invoice]] =
            invoices.findByTenant(tenantId)

          def getInvoiceWithLines(invoiceId: UUID): F[Option[(Invoice, List[LineItem])]] =
            invoices
              .findById(invoiceId)
              .flatMap {
                case None    => none[(Invoice, List[LineItem])].pure[F]
                case Some(i) => invoices.getLineItems(invoiceId).map(lines => (i, lines).some)
              }

        }
    }

}
