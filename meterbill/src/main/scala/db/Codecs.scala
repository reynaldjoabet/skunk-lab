package meterbill.db

import io.circe.Json
import meterbill.domain._
import skunk._
import skunk.circe.codec.json.jsonb
import skunk.codec.`enum`.`enum`
import skunk.codec.all._
import skunk.data.Type

object Codecs {

  // Output-column decoders must match the declared length (varchar(n)), or
  // skunk rejects the statement at prepare time with ColumnAlignmentException.

  val planTier: Codec[PlanTier] = `enum`(_.label, PlanTier.fromLabel, Type("plan_tier"))

  val billingPeriod: Codec[BillingPeriod] =
    `enum`(_.label, BillingPeriod.fromLabel, Type("billing_period"))

  val invoiceStatus: Codec[InvoiceStatus] =
    `enum`(_.label, InvoiceStatus.fromLabel, Type("invoice_status"))

  val subStatus: Codec[SubStatus] = `enum`(_.label, SubStatus.fromLabel, Type("sub_status"))

  val tenant: Decoder[Tenant] =
    (uuid *: varchar(63) *: varchar(255) *: varchar(255) *: jsonb *: timestamptz).to[Tenant]

  val plan: Decoder[Plan] =
    (uuid *: varchar(
      100
    ) *: planTier *: billingPeriod *: int8 *: int8 *: int8 *: bool *: timestamptz)
      .to[Plan]

  val subscription: Decoder[Subscription] =
    (uuid *: uuid *: uuid *: subStatus *: timestamptz *: timestamptz *: timestamptz *: timestamptz.opt *: timestamptz)
      .to[Subscription]

  val usageEvent: Decoder[UsageEvent] =
    (uuid *: uuid *: uuid *: varchar(50) *: int8 *: varchar(255) *: timestamptz).to[UsageEvent]

  val invoice: Decoder[Invoice] =
    (uuid *: uuid *: uuid *: timestamptz *: timestamptz *: int8 *: int8 *: int8 *: int8 *: invoiceStatus *: timestamptz.opt *: timestamptz.opt *: timestamptz)
      .to[Invoice]

  val lineItem: Decoder[LineItem] =
    (uuid *: uuid *: text *: int8 *: int8 *: int8).to[LineItem]

  val usageSummary: Decoder[UsageSummary] =
    (varchar(50) *: int8).to[UsageSummary]

}
