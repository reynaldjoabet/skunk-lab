-- PostgreSQL 19 tuning and two schema fixes.
--
-- Like V2 and V3, this uses CREATE INDEX CONCURRENTLY and must not run inside
-- a transaction block (`psql -f` is fine). Requires PostgreSQL 18+ for
-- uuidv7(); the application's usage insert requires 19 (ON CONFLICT DO SELECT).

-- ─── Idempotency keys are scoped to a tenant ───
-- The key was globally unique, so one tenant could block another's keys and
-- learn that a key was in use elsewhere. Keys are now unique per tenant, which
-- is also the arbiter for the application's single-statement
--   INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO SELECT
-- that records an event or returns the stored one in one round trip.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_usage_tenant_idempotency_key
  ON usage_events (tenant_id, idempotency_key);

ALTER TABLE usage_events DROP CONSTRAINT IF EXISTS usage_events_idempotency_key_key;

-- ─── A tenant can subscribe to a plan again after canceling ───
-- UNIQUE (tenant_id, plan_id) outlived its purpose: it made a canceled
-- subscription block re-subscribing to the same plan forever (reported as
-- "already has an active subscription"). V3's partial unique index is what
-- enforces one *active* subscription per tenant.
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_tenant_id_plan_id_key;

-- ─── Time-ordered primary keys ───
-- gen_random_uuid() scatters inserts across the whole primary key index, so
-- every insert dirties a random leaf page. uuidv7() keys are ordered by time:
-- inserts append to the right-most leaf, keeping the index compact and hot in
-- cache. Existing rows keep their ids; only new rows change.
ALTER TABLE tenants        ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE plans          ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE subscriptions  ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE usage_events   ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE invoices       ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE line_items     ALTER COLUMN id SET DEFAULT uuidv7();

-- ─── Keep usage reads index-only ───
-- usage_events is append-only, so autovacuum would otherwise run only after
-- inserts reach 20% of the table. Until it runs, new pages are not marked
-- all-visible and the covering index from V2 falls back to heap fetches.
-- PostgreSQL 19 also marks pages all-visible during ordinary reads, which
-- covers the gap between vacuums.
ALTER TABLE usage_events SET (
  autovacuum_vacuum_insert_scale_factor = 0.02,
  autovacuum_analyze_scale_factor       = 0.02
);
