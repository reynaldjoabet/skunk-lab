-- Integrity rules the application relied on but the schema did not enforce.
--
-- Like V2, this uses CREATE INDEX CONCURRENTLY and must not run inside a
-- transaction block (`psql -f` is fine).

-- At most one active subscription per tenant. The service checks this before
-- inserting, but two concurrent subscribe requests can both pass that check.
-- If this fails with "could not create unique index", a tenant already has
-- two active subscriptions: cancel the extra one, drop the invalid index
-- (DROP INDEX uq_subscriptions_one_active_per_tenant) and run this again.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_subscriptions_one_active_per_tenant
  ON subscriptions (tenant_id) WHERE status = 'active';

-- GET /invoices/:id reads line items by invoice, and deleting an invoice
-- cascades to them; without this both scan the whole table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_line_items_invoice
  ON line_items (invoice_id);

-- Amounts and quantities are never negative. NOT VALID + VALIDATE checks the
-- existing rows without holding a lock that blocks writes while it scans.
ALTER TABLE plans
  ADD CONSTRAINT plans_units_and_rate_non_negative
  CHECK (included_units >= 0 AND overage_rate_cents >= 0) NOT VALID;
ALTER TABLE plans VALIDATE CONSTRAINT plans_units_and_rate_non_negative;

ALTER TABLE invoices
  ADD CONSTRAINT invoices_amounts_non_negative
  CHECK (base_amount >= 0 AND overage_units >= 0 AND overage_amount >= 0 AND total_amount >= 0)
  NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_amounts_non_negative;

ALTER TABLE invoices
  ADD CONSTRAINT invoices_period_ordered
  CHECK (period_end > period_start) NOT VALID;
ALTER TABLE invoices VALIDATE CONSTRAINT invoices_period_ordered;
