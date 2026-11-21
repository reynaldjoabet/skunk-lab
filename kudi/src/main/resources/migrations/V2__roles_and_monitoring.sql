-- =============================================================================
-- V2__roles_and_monitoring.sql — operational roles, extensions, monitoring
-- =============================================================================
-- Runs after V1__init.sql (formerly bootstrap.sql, whose name sorted
-- unpredictably against V1 in docker-entrypoint-initdb.d).
--
-- Roles are created NOLOGIN with no password. A password in a migration ends up
-- in git, in the image and on every server, so an operator enables each role
-- that is actually needed:
--     ALTER ROLE monitoring WITH LOGIN PASSWORD '...';
--
-- Target: PostgreSQL 19. The monitoring views use catalogs that changed in 17
-- (pg_stat_checkpointer), 18 (pg_stat_io WAL timings) and 19
-- (pg_stat_autovacuum_scores, pg_stat_lock).
-- =============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- =============================================================================
-- 1  ROLES
-- =============================================================================

CREATE OR REPLACE FUNCTION pg_temp.ensure_role(p_name TEXT, p_options TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = p_name) THEN
        EXECUTE format('CREATE ROLE %I %s', p_name, p_options);
    END IF;
END;
$$;

SELECT pg_temp.ensure_role('migration_user', 'NOLOGIN NOSUPERUSER CREATEDB NOCREATEROLE CONNECTION LIMIT 5');
SELECT pg_temp.ensure_role('replicator',     'NOLOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 10');
SELECT pg_temp.ensure_role('monitoring',     'NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 5');
SELECT pg_temp.ensure_role('pgbackrest',     'NOLOGIN REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE');
SELECT pg_temp.ensure_role('rewind_user',    'NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE');
SELECT pg_temp.ensure_role('pgbouncer_auth', 'NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 5');
SELECT pg_temp.ensure_role('dba_admin',      'NOLOGIN NOSUPERUSER CREATEDB CREATEROLE CONNECTION LIMIT 10');
SELECT pg_temp.ensure_role('kudi_metrics',   'NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 5');
-- Members of auditor are audited by pgaudit (when installed) on every object
-- granted to it: GRANT SELECT ON auth.credentials TO auditor;
SELECT pg_temp.ensure_role('auditor',        'NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE');

ALTER ROLE migration_user SET statement_timeout = '600s';
ALTER ROLE migration_user SET lock_timeout = '5s';          -- DDL must not queue behind traffic
ALTER ROLE monitoring     SET statement_timeout = '30s';
ALTER ROLE kudi_metrics   SET statement_timeout = '10s';
ALTER ROLE kudi_metrics   SET lock_timeout = '2s';
ALTER ROLE kudi_metrics   SET idle_in_transaction_session_timeout = '10s';

GRANT pg_monitor, pg_read_all_stats, pg_read_all_settings TO monitoring;
GRANT pg_signal_backend, pg_monitor TO dba_admin;
GRANT pg_monitor TO kudi_metrics;
GRANT pg_use_reserved_connections TO monitoring, dba_admin, replicator;

-- pg_rewind needs only these file functions, not superuser.
GRANT EXECUTE ON FUNCTION pg_catalog.pg_ls_dir(text, boolean, boolean) TO rewind_user;
GRANT EXECUTE ON FUNCTION pg_catalog.pg_stat_file(text, boolean) TO rewind_user;
GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_binary_file(text) TO rewind_user;
GRANT EXECUTE ON FUNCTION pg_catalog.pg_read_binary_file(text, bigint, bigint, boolean) TO rewind_user;

-- Read-only access for the kudi DB metrics collector.
GRANT USAGE ON SCHEMA public, auth TO kudi_metrics;
GRANT SELECT ON
    users, wallets, transactions, ledger_entries, kyc_documents,
    auth.sessions, auth.login_attempts, auth.api_keys
    TO kudi_metrics;

COMMIT;


-- =============================================================================
-- 2  EXTENSIONS
-- =============================================================================
-- Contrib diagnostics ship with every PostgreSQL build, including the official
-- images. pgaudit and pg_cron do not, and also need shared_preload_libraries,
-- so they are installed only when both conditions hold instead of failing the
-- whole initialisation.

CREATE EXTENSION IF NOT EXISTS pg_prewarm;
CREATE EXTENSION IF NOT EXISTS pg_buffercache;
CREATE EXTENSION IF NOT EXISTS pg_visibility;
CREATE EXTENSION IF NOT EXISTS pgstattuple;
CREATE EXTENSION IF NOT EXISTS amcheck;

DO $$
DECLARE
    ext TEXT;
BEGIN
    FOREACH ext IN ARRAY ARRAY['pgaudit', 'pg_cron'] LOOP
        IF EXISTS (SELECT FROM pg_available_extensions WHERE name = ext)
           AND ext = ANY (string_to_array(replace(current_setting('shared_preload_libraries'), ' ', ''), ','))
        THEN
            EXECUTE format('CREATE EXTENSION IF NOT EXISTS %I', ext);
        ELSE
            RAISE NOTICE 'skipping extension %: not installed or not in shared_preload_libraries', ext;
        END IF;
    END LOOP;
END $$;


-- =============================================================================
-- 3  PGBOUNCER AUTH FUNCTION
-- =============================================================================
-- PgBouncer's auth_query delegates the password lookup to PostgreSQL.

CREATE SCHEMA IF NOT EXISTS pgbouncer;

CREATE OR REPLACE FUNCTION pgbouncer.get_auth(p_usename TEXT)
RETURNS TABLE (username TEXT, password TEXT)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT usename::TEXT, passwd::TEXT
    FROM pg_shadow
    WHERE usename = p_usename;
$$;

REVOKE ALL ON FUNCTION pgbouncer.get_auth(TEXT) FROM PUBLIC;
GRANT USAGE ON SCHEMA pgbouncer TO pgbouncer_auth;
GRANT EXECUTE ON FUNCTION pgbouncer.get_auth(TEXT) TO pgbouncer_auth;


-- =============================================================================
-- 4  MONITORING VIEWS
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS monitor;
GRANT USAGE ON SCHEMA monitor TO monitoring, dba_admin;

-- ---- Active queries ----
CREATE OR REPLACE VIEW monitor.active_queries AS
SELECT
    pid, usename, datname, client_addr, backend_type, state,
    wait_event_type, wait_event, query_id,
    now() - query_start AS query_duration,
    now() - xact_start  AS xact_duration,
    left(query, 500)    AS query_preview
FROM pg_stat_activity
WHERE state <> 'idle'
  AND pid <> pg_backend_pid()
ORDER BY query_start NULLS LAST;

-- ---- Replication ----
CREATE OR REPLACE VIEW monitor.replication_status AS
SELECT
    client_addr, application_name, state, sync_state,
    sent_lsn, write_lsn, flush_lsn, replay_lsn,
    pg_wal_lsn_diff(sent_lsn, replay_lsn) AS replay_lag_bytes,
    write_lag, flush_lag, replay_lag
FROM pg_stat_replication
ORDER BY application_name;

-- Slots that pin WAL. An abandoned slot keeps WAL forever and fills the disk,
-- which is why this migration no longer pre-creates standby slots.
CREATE OR REPLACE VIEW monitor.replication_slots AS
SELECT
    slot_name, slot_type, active, wal_status,
    pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal
FROM pg_replication_slots
ORDER BY pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) DESC NULLS LAST;

-- ---- Dead tuples ----
CREATE OR REPLACE VIEW monitor.table_bloat AS
SELECT
    schemaname, relname, n_live_tup, n_dead_tup,
    round(100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
    last_vacuum, last_autovacuum, last_analyze, last_autoanalyze,
    vacuum_count + autovacuum_count AS total_vacuums
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;

-- ---- Autovacuum priority (PostgreSQL 19) ----
-- What autovacuum will process next and why; a table near the top that never
-- gets processed means workers are saturated.
CREATE OR REPLACE VIEW monitor.autovacuum_priority AS
SELECT
    schemaname, relname,
    round(score::NUMERIC, 3)               AS score,
    round(vacuum_score::NUMERIC, 3)        AS vacuum_score,
    round(vacuum_insert_score::NUMERIC, 3) AS vacuum_insert_score,
    round(analyze_score::NUMERIC, 3)       AS analyze_score,
    round(xid_score::NUMERIC, 3)           AS xid_score,
    do_vacuum, do_analyze, for_wraparound
FROM pg_stat_autovacuum_scores
WHERE do_vacuum OR do_analyze
ORDER BY score DESC;

-- ---- Lock contention ----
-- pg_blocking_pids() is exact and avoids the pg_locks self-join.
CREATE OR REPLACE VIEW monitor.lock_contention AS
SELECT
    blocked.pid      AS blocked_pid,
    blocked.usename  AS blocked_user,
    blocking.pid     AS blocking_pid,
    blocking.usename AS blocking_user,
    blocked.query    AS blocked_query,
    blocking.query   AS blocking_query,
    now() - blocked.query_start AS blocked_duration
FROM pg_stat_activity blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(pid)
JOIN pg_stat_activity blocking ON blocking.pid = b.pid
ORDER BY blocked_duration DESC;

-- Cumulative lock waits by lock type (PostgreSQL 19).
CREATE OR REPLACE VIEW monitor.lock_waits AS
SELECT locktype, waits, round(wait_time::NUMERIC, 1) AS wait_time_ms,
       fastpath_exceeded, stats_reset
FROM pg_stat_lock
WHERE waits > 0
ORDER BY wait_time DESC;

-- ---- Top queries ----
CREATE OR REPLACE VIEW monitor.top_queries AS
SELECT
    queryid, calls,
    round(total_exec_time::NUMERIC, 2)  AS total_time_ms,
    round(mean_exec_time::NUMERIC, 2)   AS avg_time_ms,
    round(stddev_exec_time::NUMERIC, 2) AS stddev_ms,
    rows,
    round(100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0), 1) AS cache_hit_pct,
    temp_blks_written,
    left(query, 300) AS query_preview
FROM pg_stat_statements
WHERE calls > 10
ORDER BY total_exec_time DESC
LIMIT 100;

-- ---- Checkpoints (pg_stat_checkpointer since 17) ----
CREATE OR REPLACE VIEW monitor.checkpoint_stats AS
SELECT
    num_timed, num_requested, num_done,
    round(write_time::NUMERIC / 1000, 2) AS write_time_sec,
    round(sync_time::NUMERIC / 1000, 2)  AS sync_time_sec,
    buffers_written, slru_written, stats_reset
FROM pg_stat_checkpointer;

-- ---- WAL volume (timings moved to pg_stat_io in 18) ----
CREATE OR REPLACE VIEW monitor.wal_stats AS
SELECT
    wal_records, wal_fpi, wal_bytes,
    pg_size_pretty(wal_bytes)     AS wal_bytes_pretty,
    pg_size_pretty(wal_fpi_bytes) AS wal_fpi_bytes_pretty,  -- full-page images (19)
    wal_buffers_full, stats_reset
FROM pg_stat_wal;

-- ---- I/O by backend type ----
CREATE OR REPLACE VIEW monitor.io_stats AS
SELECT
    backend_type, object, context,
    reads, pg_size_pretty(read_bytes)   AS read_bytes,  round(read_time::NUMERIC, 1)  AS read_time_ms,
    writes, pg_size_pretty(write_bytes) AS write_bytes, round(write_time::NUMERIC, 1) AS write_time_ms,
    hits, evictions, fsyncs
FROM pg_stat_io
WHERE coalesce(reads, 0) + coalesce(writes, 0) > 0
ORDER BY coalesce(read_time, 0) + coalesce(write_time, 0) DESC;

GRANT SELECT ON ALL TABLES IN SCHEMA monitor TO monitoring, dba_admin;


-- =============================================================================
-- 5  SECURITY HARDENING
-- =============================================================================

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC;

-- Audit trail of schema changes.
CREATE TABLE IF NOT EXISTS monitor.ddl_audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    username        TEXT        NOT NULL,
    client_addr     TEXT,
    event           TEXT        NOT NULL,
    tag             TEXT        NOT NULL,
    object_type     TEXT,
    schema_name     TEXT,
    object_identity TEXT
);

CREATE INDEX IF NOT EXISTS idx_ddl_audit_time ON monitor.ddl_audit_log (event_time);

CREATE OR REPLACE FUNCTION monitor.log_ddl_event()
RETURNS event_trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO monitor.ddl_audit_log
        (username, client_addr, event, tag, object_type, schema_name, object_identity)
    SELECT session_user, inet_client_addr()::TEXT, TG_EVENT, TG_TAG,
           r.object_type, r.schema_name, r.object_identity
    FROM pg_event_trigger_ddl_commands() AS r;
END;
$$;

DROP EVENT TRIGGER IF EXISTS audit_ddl_commands;
CREATE EVENT TRIGGER audit_ddl_commands ON ddl_command_end
    EXECUTE FUNCTION monitor.log_ddl_event();

GRANT SELECT ON monitor.ddl_audit_log TO monitoring, dba_admin;


-- =============================================================================
-- Replication slots are deliberately not created here. Create a standby's slot
-- when the standby exists (Patroni does this itself):
--   SELECT pg_create_physical_replication_slot('slot_standby1', true);
-- =============================================================================
