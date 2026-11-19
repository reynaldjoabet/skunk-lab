package meterbill.service

import java.time.OffsetDateTime

import meterbill.domain._

/**
  * Pure pricing rules, kept free of I/O so they can be unit tested.
  */
object InvoiceCalculator {

  /**
    * Prices one billing period: the plan's base price plus overage on usage beyond the included
    * units. Amounts are in cents. Uses exact arithmetic, so an overflow throws instead of silently
    * wrapping into a wrong (possibly negative) amount.
    */
  def price(plan: Plan, usage: List[UsageSummary]): InvoicePreview = {
    val totalUsage    = usage.foldLeft(0L)((acc, m) => Math.addExact(acc, m.totalQuantity))
    val overageUnits  = math.max(0L, Math.subtractExact(totalUsage, plan.includedUnits))
    val overageAmount = Math.multiplyExact(overageUnits, plan.overageRateCents)
    val total         = Math.addExact(plan.basePriceCents, overageAmount)

    val base = LineItemPreview(
      s"${plan.name} ${plan.period.label} base",
      1,
      plan.basePriceCents,
      plan.basePriceCents
    )
    val perMetric = usage.map(m => LineItemPreview(s"Usage: ${m.metric}", m.totalQuantity, 0, 0))
    val overage   =
      if (overageUnits > 0)
        List(
          LineItemPreview(
            s"Overage ($overageUnits units × ${plan.overageRateCents}¢)",
            overageUnits,
            plan.overageRateCents,
            overageAmount
          )
        )
      else Nil

    InvoicePreview(
      plan.basePriceCents,
      overageUnits,
      overageAmount,
      total,
      base :: perMetric ++ overage
    )
  }

  /**
    * End of the period that starts at `start`, by the plan's billing period.
    */
  def periodEnd(period: BillingPeriod, start: OffsetDateTime): OffsetDateTime =
    period match {
      case BillingPeriod.Monthly => start.plusMonths(1)
      case BillingPeriod.Yearly  => start.plusYears(1)
    }

}
