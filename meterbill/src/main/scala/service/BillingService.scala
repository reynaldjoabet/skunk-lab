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

  def make[F[_]: MonadCancelThrow: Concurrent](s: Session[F]): F[BillingService[F]] =
    (SubscriptionRepo.make(s), PlanRepo.make(s), UsageRepo.make(s), InvoiceRepo.make(s)).mapN {
      (subs, plans, usage, invoices) =>
        new BillingService[F] {

          private def calculate(sub: Subscription, plan: Plan): F[InvoicePreview] =
            usage
              .summaryForPeriod(sub.id, sub.currentPeriodStart, sub.currentPeriodEnd)
              .map { metrics =>
                val totalUsage    = metrics.map(_.totalQuantity).sum
                val overageUnits  = math.max(0L, totalUsage - plan.includedUnits)
                val overageAmount = overageUnits * plan.overageRateCents
                val total         = plan.basePriceCents + overageAmount

                val lines = List(
                  LineItemPreview(
                    s"${plan.name} ${plan.period.label} base",
                    1,
                    plan.basePriceCents,
                    plan.basePriceCents
                  )
                ) ++ metrics.map { m =>
                  LineItemPreview(s"Usage: ${m.metric}", m.totalQuantity, 0, 0)
                } ++ (if (overageUnits > 0)
                        List(
                          LineItemPreview(
                            s"Overage ($overageUnits units × ${plan.overageRateCents}¢)",
                            overageUnits,
                            plan.overageRateCents,
                            overageAmount
                          )
                        )
                      else Nil)

                InvoicePreview(plan.basePriceCents, overageUnits, overageAmount, total, lines)
              }

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
                  prev <- calculate(sub, plan)
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
                  _ <- prev
                         .lineItems
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
                         sub.currentPeriodEnd.plusMonths(1)
                       )
                } yield invoice
              }

          def markPaid(invoiceId: UUID): F[Unit] =
            invoices
              .findById(invoiceId)
              .flatMap(_.liftTo[F](InvoiceNotFound(invoiceId)))
              .flatMap(_ => invoices.markPaid(invoiceId))

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
