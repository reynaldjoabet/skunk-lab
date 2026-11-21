-- PostgreSQL 19 tuning for the token and session store.
--
-- Uses DROP INDEX CONCURRENTLY, so it must not run inside a transaction block
-- (`psql -f` is fine).
--
-- V2__grants_and_sessions.sql was previously a second "V1__" file. Two files
-- with one version number would be rejected by any migration tool; the rename
-- keeps the same execution order.

-- ─── Duplicate index ───
-- Same column as the UNIQUE (scheme) constraint's own index.
DROP INDEX CONCURRENTLY IF EXISTS ix_identity_providers_scheme;

-- ─── High-churn tables ───
-- Sessions, grants, device codes and pushed authorization requests are short
-- lived: rows are inserted, updated on renewal and deleted on expiry. At the
-- default 20% threshold, dead rows pile up between vacuums and the unique key
-- lookups on every request walk past them. Vacuum after ~2% instead, and
-- vacuum their indexes in parallel (PostgreSQL 19; takes effect when the
-- server's autovacuum_max_parallel_workers > 0).
ALTER TABLE idmgmt.server_side_sessions SET (
  autovacuum_vacuum_scale_factor  = 0.02,
  autovacuum_analyze_scale_factor = 0.02,
  autovacuum_parallel_workers     = 2      -- 6 indexes
);
ALTER TABLE idmgmt.persisted_grants SET (
  autovacuum_vacuum_scale_factor  = 0.02,
  autovacuum_analyze_scale_factor = 0.02,
  autovacuum_parallel_workers     = 2      -- 6 indexes
);
ALTER TABLE idmgmt.device_codes SET (
  autovacuum_vacuum_scale_factor = 0.02
);
ALTER TABLE idmgmt.pushed_authorization_requests SET (
  autovacuum_vacuum_scale_factor = 0.02
);

-- Space already lost to bloat is not returned by VACUUM. Reclaim it online
-- with deploy/sql/repack_bloated.sql (REPACK (CONCURRENTLY), PostgreSQL 19),
-- which replaces VACUUM FULL and pg_repack without blocking reads or writes.
