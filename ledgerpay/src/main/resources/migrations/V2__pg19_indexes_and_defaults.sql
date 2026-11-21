-- PostgreSQL 19 tuning: drop duplicate indexes, time-ordered keys, append-only
-- autovacuum.
--
-- Uses DROP INDEX CONCURRENTLY, so it must not run inside a transaction block
-- (`psql -f` is fine). Requires PostgreSQL 18+ for uuidv7().

-- ─── Duplicate indexes ───
-- Each of these duplicates the index behind a UNIQUE constraint, so every
-- insert maintained two identical B-trees for no read benefit.
DROP INDEX CONCURRENTLY IF EXISTS idx_users_email;     -- = users_email_key
DROP INDEX CONCURRENTLY IF EXISTS idx_tx_idempotency;  -- = transactions_idempotency_key_key
-- Leading column of UNIQUE (user_id, currency) already serves user_id lookups.
DROP INDEX CONCURRENTLY IF EXISTS idx_accounts_user;

-- ─── Time-ordered primary keys ───
-- uuidv7() keys append to the right-most index leaf instead of landing on a
-- random page, which keeps the primary key index compact and cached on the
-- insert-heavy transactions table. Existing rows keep their ids.
ALTER TABLE users        ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE accounts     ALTER COLUMN id SET DEFAULT uuidv7();
ALTER TABLE transactions ALTER COLUMN id SET DEFAULT uuidv7();

-- ─── Append-only tables ───
-- Vacuum on inserts often enough to keep the visibility map current, so reads
-- by (source/dest account, created_at) and audit lookups stay index-only.
ALTER TABLE transactions SET (autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE audit_log    SET (autovacuum_vacuum_insert_scale_factor = 0.05);
