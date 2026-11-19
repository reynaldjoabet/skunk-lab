package meterbill.service

import java.time.OffsetDateTime
import java.util.UUID

import meterbill.domain._

class InvoiceCalculatorSuite extends munit.FunSuite {

  private val start = OffsetDateTime.parse("2026-01-01T00:00:00Z")

  private def plan(
      base: Long,
      included: Long,
      rate: Long,
      period: BillingPeriod = BillingPeriod.Monthly
  ): Plan =
    Plan(UUID.randomUUID(), "Starter", PlanTier.Starter, period, base, included, rate, true, start)

  test("usage within the included units costs only the base price") {
    val p = InvoiceCalculator.price(plan(2900, 50000, 5), List(UsageSummary("api_calls", 50000)))
    assertEquals(p.overageUnits, 0L)
    assertEquals(p.overageAmount, 0L)
    assertEquals(p.totalAmount, 2900L)
    assertEquals(p.lineItems.map(_.description), List("Starter monthly base", "Usage: api_calls"))
  }

  test("overage is charged on the total across metrics beyond the included units") {
    val usage = List(UsageSummary("api_calls", 40000), UsageSummary("storage", 10100))
    val p     = InvoiceCalculator.price(plan(2900, 50000, 5), usage)
    assertEquals(p.overageUnits, 100L)
    assertEquals(p.overageAmount, 500L)
    assertEquals(p.totalAmount, 3400L)
    assertEquals(p.lineItems.last.amountCents, 500L)
  }

  test("line item amounts add up to the invoice total") {
    val usage = List(UsageSummary("api_calls", 75000), UsageSummary("storage", 3))
    val p     = InvoiceCalculator.price(plan(9900, 50000, 2), usage)
    assertEquals(p.lineItems.map(_.amountCents).sum, p.totalAmount)
  }

  test("no usage produces a base-only invoice") {
    val p = InvoiceCalculator.price(plan(0, 1000, 0), Nil)
    assertEquals(p.totalAmount, 0L)
    assertEquals(p.lineItems.size, 1)
  }

  test("an amount too large for a Long fails instead of wrapping") {
    intercept[ArithmeticException] {
      InvoiceCalculator.price(plan(0, 0, Long.MaxValue), List(UsageSummary("api_calls", 2)))
    }
  }

  test("the next period follows the plan's billing period") {
    assertEquals(
      InvoiceCalculator.periodEnd(BillingPeriod.Monthly, start),
      OffsetDateTime.parse("2026-02-01T00:00:00Z")
    )
    assertEquals(
      InvoiceCalculator.periodEnd(BillingPeriod.Yearly, start),
      OffsetDateTime.parse("2027-01-01T00:00:00Z")
    )
  }

}
