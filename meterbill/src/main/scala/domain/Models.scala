package meterbill.domain

import java.time.OffsetDateTime
import java.util.UUID

import io.circe.Json

// ─── Enums ───

enum PlanTier(val label: String) derives CanEqual {

  case Free       extends PlanTier("free")
  case Starter    extends PlanTier("starter")
  case Pro        extends PlanTier("pro")
  case Enterprise extends PlanTier("enterprise")

}

object PlanTier {
  def fromLabel(s: String): Option[PlanTier] = values.find(_.label == s)
}

enum BillingPeriod(val label: String) derives CanEqual {

  case Monthly extends BillingPeriod("monthly")
  case Yearly  extends BillingPeriod("yearly")

}

object BillingPeriod {
  def fromLabel(s: String): Option[BillingPeriod] = values.find(_.label == s)
}

enum InvoiceStatus(val label: String) derives CanEqual {

  case Draft         extends InvoiceStatus("draft")
  case Open          extends InvoiceStatus("open")
  case Paid          extends InvoiceStatus("paid")
  case Void          extends InvoiceStatus("void")
  case Uncollectible extends InvoiceStatus("uncollectible")

}

object InvoiceStatus {
  def fromLabel(s: String): Option[InvoiceStatus] = values.find(_.label == s)
}

enum SubStatus(val label: String) derives CanEqual {

  case Active   extends SubStatus("active")
  case PastDue  extends SubStatus("past_due")
  case Canceled extends SubStatus("canceled")
  case Trialing extends SubStatus("trialing")

}

object SubStatus {
  def fromLabel(s: String): Option[SubStatus] = values.find(_.label == s)
}

// ─── Models ───

case class Tenant(
    id: UUID,
    slug: String,
    name: String,
    email: String,
    metadata: Json,
    createdAt: OffsetDateTime
)

case class Plan(
    id: UUID,
    name: String,
    tier: PlanTier,
    period: BillingPeriod,
    basePriceCents: Long,
    includedUnits: Long,
    overageRateCents: Long,
    active: Boolean,
    createdAt: OffsetDateTime
)

case class Subscription(
    id: UUID,
    tenantId: UUID,
    planId: UUID,
    status: SubStatus,
    startsAt: OffsetDateTime,
    currentPeriodStart: OffsetDateTime,
    currentPeriodEnd: OffsetDateTime,
    canceledAt: Option[OffsetDateTime],
    createdAt: OffsetDateTime
)

case class UsageEvent(
    id: UUID,
    tenantId: UUID,
    subscriptionId: UUID,
    metric: String,
    quantity: Long,
    idempotencyKey: String,
    recordedAt: OffsetDateTime
)

case class Invoice(
    id: UUID,
    tenantId: UUID,
    subscriptionId: UUID,
    periodStart: OffsetDateTime,
    periodEnd: OffsetDateTime,
    baseAmount: Long,
    overageUnits: Long,
    overageAmount: Long,
    totalAmount: Long,
    status: InvoiceStatus,
    issuedAt: Option[OffsetDateTime],
    paidAt: Option[OffsetDateTime],
    createdAt: OffsetDateTime
)

case class LineItem(
    id: UUID,
    invoiceId: UUID,
    description: String,
    quantity: Long,
    unitPriceCents: Long,
    amountCents: Long
)

// ─── Request DTOs ───

case class CreateTenantReq(slug: String, name: String, email: String)
case class SubscribeReq(planId: UUID)
case class RecordUsageReq(idempotencyKey: String, metric: String, quantity: Long)
case class RecordUsageBatchReq(events: List[RecordUsageReq])

case class GenerateInvoiceReq(
    subscriptionId: UUID,
    periodStart: OffsetDateTime,
    periodEnd: OffsetDateTime
)

// ─── Response DTOs ───

case class UsageSummary(metric: String, totalQuantity: Long)

case class InvoicePreview(
    baseAmount: Long,
    overageUnits: Long,
    overageAmount: Long,
    totalAmount: Long,
    lineItems: List[LineItemPreview]
)

case class LineItemPreview(
    description: String,
    quantity: Long,
    unitPriceCents: Long,
    amountCents: Long
)
