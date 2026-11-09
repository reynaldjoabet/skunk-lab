-- Read-path indexes for usage_events, found by load testing.
--
-- Usage events from many tenants arrive interleaved, so one tenant's rows are
-- spread over roughly one heap page each. Both hot reads below were fetching
-- every row of the tenant from the heap (~1,000 pages per call at 1,000
-- events/tenant).
--
-- CONCURRENTLY so this can run against a live database without blocking
-- inserts; it must not run inside a transaction block (psql runs files in
-- autocommit mode, so `psql -f` is fine).

-- GET /tenants/:id/usage/summary (and BillingService totals): covering index,
-- so SUM(quantity) GROUP BY metric is answered by an index-only scan instead
-- of a heap fetch per event. Supersedes idx_usage_sub_period (same key).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_usage_sub_period_covering
  ON usage_events (subscription_id, recorded_at) INCLUDE (metric, quantity);

DROP INDEX CONCURRENTLY IF EXISTS idx_usage_sub_period;

-- GET /tenants/:id/usage/recent: ORDER BY recorded_at DESC LIMIT n can stop
-- after n index entries. The existing (tenant_id, metric, recorded_at) index
-- cannot provide that order across metrics, so every row was read and sorted.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_usage_tenant_recent
  ON usage_events (tenant_id, recorded_at DESC);
