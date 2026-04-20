package meterbill.domain

import java.time.OffsetDateTime
import java.util.UUID

import io.circe.Json

// ─── Enums ───

sealed abstract class PlanTier(val label: String)
object PlanTier {

  case object Free       extends PlanTier("free")
  case object Starter    extends PlanTier("starter")
  case object Pro        extends PlanTier("pro")
  case object Enterprise extends PlanTier("enterprise")
  val values: List[PlanTier]                 = List(Free, Starter, Pro, Enterprise)
  def fromLabel(s: String): Option[PlanTier] = values.find(_.label == s)

}

sealed abstract class BillingPeriod(val label: String)
object BillingPeriod {

  case object Monthly extends BillingPeriod("monthly")
  case object Yearly  extends BillingPeriod("yearly")
  val values: List[BillingPeriod]                 = List(Monthly, Yearly)
  def fromLabel(s: String): Option[BillingPeriod] = values.find(_.label == s)

}

sealed abstract class InvoiceStatus(val label: String)
object InvoiceStatus {

  case object Draft         extends InvoiceStatus("draft")
  case object Open          extends InvoiceStatus("open")
  case object Paid          extends InvoiceStatus("paid")
  case object Void          extends InvoiceStatus("void")
  case object Uncollectible extends InvoiceStatus("uncollectible")
  val values: List[InvoiceStatus]                 = List(Draft, Open, Paid, Void, Uncollectible)
  def fromLabel(s: String): Option[InvoiceStatus] = values.find(_.label == s)

}

sealed abstract class SubStatus(val label: String)
object SubStatus {

  case object Active   extends SubStatus("active")
  case object PastDue  extends SubStatus("past_due")
  case object Canceled extends SubStatus("canceled")
  case object Trialing extends SubStatus("trialing")
  val values: List[SubStatus]                 = List(Active, PastDue, Canceled, Trialing)
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
