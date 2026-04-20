-- =============================================================================
-- PostgreSQL 18 — Bootstrap: Roles, Extensions, Security & Monitoring
-- =============================================================================
-- Run this ONCE on the primary after cluster initialization.
-- psql -U postgres -d postgres -f bootstrap.sql
-- =============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- =============================================================================
-- 1  ROLES & PERMISSIONS
-- =============================================================================
-- Principle of least privilege. No role gets superuser except postgres.

-- ---- Application Role (read-write OLTP) ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        CREATE ROLE app_user WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 200;
    END IF;
END $$;

ALTER ROLE app_user SET statement_timeout = '30s';
ALTER ROLE app_user SET lock_timeout = '10s';
ALTER ROLE app_user SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE app_user SET work_mem = '32MB';

-- ---- Read-Only Role (for standbys / reporting) ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'readonly_user') THEN
        CREATE ROLE readonly_user WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 300;
    END IF;
END $$;

ALTER ROLE readonly_user SET statement_timeout = '300s';
ALTER ROLE readonly_user SET work_mem = '256MB';
ALTER ROLE readonly_user SET default_transaction_read_only = on;

-- ---- Analytics / Batch Role (long queries, more memory) ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analytics_user') THEN
        CREATE ROLE analytics_user WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 20;
    END IF;
END $$;

ALTER ROLE analytics_user SET statement_timeout = '3600s';
ALTER ROLE analytics_user SET work_mem = '512MB';
ALTER ROLE analytics_user SET maintenance_work_mem = '2GB';
ALTER ROLE analytics_user SET effective_io_concurrency = 300;
ALTER ROLE analytics_user SET max_parallel_workers_per_gather = 8;
ALTER ROLE analytics_user SET default_transaction_read_only = on;

-- ---- Migration / DDL Role ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migration_user') THEN
        CREATE ROLE migration_user WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER CREATEDB NOCREATEROLE
            CONNECTION LIMIT 5;
    END IF;
END $$;

ALTER ROLE migration_user SET statement_timeout = '600s';
ALTER ROLE migration_user SET lock_timeout = '5s';   -- DDL should not wait long

-- ---- Replication Role ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator WITH LOGIN REPLICATION PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 10;
    END IF;
END $$;

-- ---- Monitoring Role (Prometheus / Datadog / pgwatch2) ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'monitoring') THEN
        CREATE ROLE monitoring WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 5;
    END IF;
END $$;

GRANT pg_monitor TO monitoring;
GRANT pg_read_all_stats TO monitoring;
GRANT pg_read_all_settings TO monitoring;

ALTER ROLE monitoring SET statement_timeout = '30s';

-- ---- pgBackRest Backup Role ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbackrest') THEN
        CREATE ROLE pgbackrest WITH LOGIN REPLICATION PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

-- ---- pg_rewind Role (for Patroni) ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rewind_user') THEN
        CREATE ROLE rewind_user WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

GRANT EXECUTE ON function pg_catalog.pg_ls_dir(text, boolean, boolean) TO rewind_user;
GRANT EXECUTE ON function pg_catalog.pg_stat_file(text, boolean) TO rewind_user;
GRANT EXECUTE ON function pg_catalog.pg_read_binary_file(text) TO rewind_user;
GRANT EXECUTE ON function pg_catalog.pg_read_binary_file(text, bigint, bigint, boolean) TO rewind_user;

-- ---- PgBouncer Auth Role ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbouncer_auth') THEN
        CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            CONNECTION LIMIT 5;
    END IF;
END $$;

-- ---- DBA Admin Role ----
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dba_admin') THEN
        CREATE ROLE dba_admin WITH LOGIN PASSWORD 'CHANGE_ME'
            NOSUPERUSER CREATEDB CREATEROLE
            CONNECTION LIMIT 10;
    END IF;
END $$;

GRANT pg_signal_backend TO dba_admin;
GRANT pg_monitor TO dba_admin;

-- ---- Auditor Role (for pgAudit role-based auditing) ----
-- Any table/function GRANTed to this role will be fully audited
-- regardless of the pgaudit.log setting. Use for PCI/SOC2 sensitive tables.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auditor') THEN
        CREATE ROLE auditor NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END $$;

-- Example: audit all access to a sensitive table:
-- GRANT SELECT, INSERT, UPDATE, DELETE ON sensitive_table TO auditor;

-- ---- Reserved Connection Role (PG18) ----
GRANT pg_use_reserved_connections TO monitoring;
GRANT pg_use_reserved_connections TO dba_admin;
GRANT pg_use_reserved_connections TO replicator;

COMMIT;

-- =============================================================================
-- 2  EXTENSIONS
-- =============================================================================

-- Core monitoring
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pg_prewarm;

-- Audit & compliance (requires shared_preload_libraries)
CREATE EXTENSION IF NOT EXISTS pgaudit;

-- In-database job scheduler (requires shared_preload_libraries)
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Data types & indexing
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS btree_gin;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- UUID generation
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Utilities
CREATE EXTENSION IF NOT EXISTS pageinspect;
CREATE EXTENSION IF NOT EXISTS pg_buffercache;
CREATE EXTENSION IF NOT EXISTS pg_freespacemap;
CREATE EXTENSION IF NOT EXISTS pg_visibility;
CREATE EXTENSION IF NOT EXISTS pgstattuple;
CREATE EXTENSION IF NOT EXISTS amcheck;

-- [OPTIONAL] Uncomment as needed:
-- CREATE EXTENSION IF NOT EXISTS pg_partman;
-- CREATE EXTENSION IF NOT EXISTS hstore;
-- CREATE EXTENSION IF NOT EXISTS citext;
-- CREATE EXTENSION IF NOT EXISTS ltree;
-- CREATE EXTENSION IF NOT EXISTS postgis;


-- =============================================================================
-- 3  PGBOUNCER AUTH FUNCTION
-- =============================================================================
-- PgBouncer auth_query delegates password lookup to PostgreSQL.

CREATE SCHEMA IF NOT EXISTS pgbouncer;

CREATE OR REPLACE FUNCTION pgbouncer.get_auth(p_usename TEXT)
RETURNS TABLE(username TEXT, password TEXT) AS
$$
BEGIN
    RETURN QUERY
    SELECT usename::TEXT, passwd::TEXT
    FROM pg_catalog.pg_shadow
    WHERE usename = p_usename;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

REVOKE ALL ON FUNCTION pgbouncer.get_auth(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pgbouncer.get_auth(TEXT) TO pgbouncer_auth;


-- =============================================================================
-- 4  MONITORING VIEWS
-- =============================================================================
-- Pre-built views for dashboards and alerting.

CREATE SCHEMA IF NOT EXISTS monitor;
GRANT USAGE ON SCHEMA monitor TO monitoring;

-- ---- Active Queries ----
CREATE OR REPLACE VIEW monitor.active_queries AS
SELECT
    pid,
    usename,
    datname,
    client_addr,
    backend_type,
    state,
    wait_event_type,
    wait_event,
    query_id,
    now() - query_start AS query_duration,
    now() - xact_start AS xact_duration,
    left(query, 500) AS query_preview
FROM pg_stat_activity
WHERE state != 'idle'
  AND pid != pg_backend_pid()
ORDER BY query_start ASC NULLS LAST;

GRANT SELECT ON monitor.active_queries TO monitoring;

-- ---- Replication Lag ----
CREATE OR REPLACE VIEW monitor.replication_status AS
SELECT
    client_addr,
    application_name,
    state,
    sync_state,
    sent_lsn,
    write_lsn,
    flush_lsn,
    replay_lsn,
    pg_wal_lsn_diff(sent_lsn, replay_lsn) AS replay_lag_bytes,
    write_lag,
    flush_lag,
    replay_lag
FROM pg_stat_replication
ORDER BY application_name;

GRANT SELECT ON monitor.replication_status TO monitoring;

-- ---- Table Bloat Estimate ----
CREATE OR REPLACE VIEW monitor.table_bloat AS
SELECT
    schemaname,
    relname,
    n_live_tup,
    n_dead_tup,
    CASE WHEN n_live_tup > 0
         THEN round(100.0 * n_dead_tup / (n_live_tup + n_dead_tup), 1)
         ELSE 0
    END AS dead_pct,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze,
    vacuum_count + autovacuum_count AS total_vacuums
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;

GRANT SELECT ON monitor.table_bloat TO monitoring;

-- ---- Lock Contention ----
CREATE OR REPLACE VIEW monitor.lock_contention AS
SELECT
    blocked.pid AS blocked_pid,
    blocked.usename AS blocked_user,
    blocking.pid AS blocking_pid,
    blocking.usename AS blocking_user,
    blocked.query AS blocked_query,
    blocking.query AS blocking_query,
    now() - blocked.query_start AS blocked_duration
FROM pg_stat_activity blocked
JOIN pg_locks bl ON bl.pid = blocked.pid AND NOT bl.granted
JOIN pg_locks gl ON gl.locktype = bl.locktype
    AND gl.database IS NOT DISTINCT FROM bl.database
    AND gl.relation IS NOT DISTINCT FROM bl.relation
    AND gl.page IS NOT DISTINCT FROM bl.page
    AND gl.tuple IS NOT DISTINCT FROM bl.tuple
    AND gl.virtualxid IS NOT DISTINCT FROM bl.virtualxid
    AND gl.transactionid IS NOT DISTINCT FROM bl.transactionid
    AND gl.classid IS NOT DISTINCT FROM bl.classid
    AND gl.objid IS NOT DISTINCT FROM bl.objid
    AND gl.objsubid IS NOT DISTINCT FROM bl.objsubid
    AND gl.pid != bl.pid
    AND gl.granted
JOIN pg_stat_activity blocking ON blocking.pid = gl.pid
ORDER BY blocked_duration DESC;

GRANT SELECT ON monitor.lock_contention TO monitoring;

-- ---- Top Queries by Time (pg_stat_statements) ----
CREATE OR REPLACE VIEW monitor.top_queries AS
SELECT
    queryid,
    calls,
    round(total_exec_time::numeric, 2) AS total_time_ms,
    round(mean_exec_time::numeric, 2) AS avg_time_ms,
    round(stddev_exec_time::numeric, 2) AS stddev_ms,
    rows,
    round((100.0 * shared_blks_hit / NULLIF(shared_blks_hit + shared_blks_read, 0))::numeric, 1) AS cache_hit_pct,
    temp_blks_written,
    left(query, 300) AS query_preview
FROM pg_stat_statements
WHERE calls > 10
ORDER BY total_exec_time DESC
LIMIT 100;

GRANT SELECT ON monitor.top_queries TO monitoring;

-- ---- Checkpoint Health ----
CREATE OR REPLACE VIEW monitor.checkpoint_stats AS
SELECT
    checkpoints_timed,
    checkpoints_req,
    round(checkpoint_write_time::numeric / 1000, 2) AS write_time_sec,
    round(checkpoint_sync_time::numeric / 1000, 2) AS sync_time_sec,
    buffers_checkpoint,
    buffers_clean,
    buffers_backend,
    buffers_backend_fsync,
    maxwritten_clean,
    stats_reset
FROM pg_stat_bgwriter;

GRANT SELECT ON monitor.checkpoint_stats TO monitoring;

-- ---- WAL Generation Rate ----
CREATE OR REPLACE VIEW monitor.wal_stats AS
SELECT
    wal_records,
    wal_fpi,
    wal_bytes,
    pg_size_pretty(wal_bytes) AS wal_bytes_pretty,
    wal_buffers_full,
    wal_write,
    wal_sync,
    round(wal_write_time::numeric, 2) AS write_time_ms,
    round(wal_sync_time::numeric, 2) AS sync_time_ms,
    stats_reset
FROM pg_stat_wal;

GRANT SELECT ON monitor.wal_stats TO monitoring;


-- =============================================================================
-- 5  TEMP TABLESPACE
-- =============================================================================
-- Point temp operations to fast ephemeral storage.

-- CREATE TABLESPACE temp_tblspc LOCATION '/tmp/pg/tablespace';
-- ALTER DATABASE mydb SET temp_tablespaces = 'temp_tblspc';


-- =============================================================================
-- 6  REPLICATION SLOTS
-- =============================================================================
-- Pre-create physical replication slots for standbys.
-- Patroni normally manages this, but explicit creation is useful
-- when synchronized_standby_slots references them.

SELECT pg_create_physical_replication_slot('slot_standby1', true)
WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'slot_standby1');

SELECT pg_create_physical_replication_slot('slot_standby2', true)
WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'slot_standby2');

SELECT pg_create_physical_replication_slot('slot_standby3', true)
WHERE NOT EXISTS (SELECT FROM pg_replication_slots WHERE slot_name = 'slot_standby3');


-- =============================================================================
-- 7  SECURITY HARDENING
-- =============================================================================

-- Revoke default public access to new databases
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Only explicit grants should allow schema access
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC;

-- Log all DDL and privilege changes (event trigger)
-- This creates an audit trail for schema modifications.

CREATE TABLE IF NOT EXISTS monitor.ddl_audit_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_time timestamptz NOT NULL DEFAULT now(),
    username text NOT NULL,
    client_addr text,
    event text NOT NULL,
    tag text NOT NULL,
    object_type text,
    schema_name text,
    object_identity text
);

CREATE INDEX IF NOT EXISTS idx_ddl_audit_time ON monitor.ddl_audit_log (event_time);

CREATE OR REPLACE FUNCTION monitor.log_ddl_event()
RETURNS event_trigger
LANGUAGE plpgsql SECURITY DEFINER AS
$$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT * FROM pg_event_trigger_ddl_commands()
    LOOP
        INSERT INTO monitor.ddl_audit_log
            (username, client_addr, event, tag, object_type, schema_name, object_identity)
        VALUES
            (current_user, inet_client_addr()::text, TG_EVENT, TG_TAG,
             r.object_type, r.schema_name, r.object_identity);
    END LOOP;
END;
$$;

DROP EVENT TRIGGER IF EXISTS audit_ddl_commands;
CREATE EVENT TRIGGER audit_ddl_commands ON ddl_command_end
    EXECUTE FUNCTION monitor.log_ddl_event();

GRANT SELECT ON monitor.ddl_audit_log TO monitoring;
GRANT SELECT ON monitor.ddl_audit_log TO dba_admin;


-- =============================================================================
-- Done
-- =============================================================================
-- Run on standby after slot sync is established:
--   SELECT * FROM monitor.replication_status;
