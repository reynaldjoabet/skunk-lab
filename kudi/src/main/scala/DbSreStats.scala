package kudi

import skunk.codec.all.*
import skunk.implicits.*
import skunk.Query
import skunk.Void

private final case class DbSreStats(
    maxConnections: Long,
    connectionSaturation: Double,
    longRunningQueries: Long,
    oldIdleInTxn: Long,
    preparedTransactions: Long,
    invalidIndexes: Long,
    tablesWithoutPrimaryKey: Long,
    tablesNeedVacuum: Long,
    tablesNeedAnalyze: Long,
    maxAutovacuumAgeSeconds: Long,
    maxAutoAnalyzeAgeSeconds: Long,
    maxFreezeAge: Long,
    inactiveReplicationSlots: Long,
    maxReplicationSlotRetainedBytes: Long,
    walRecords: Long,
    walFpi: Long,
    walBytes: Long
)

private val dbSreQ: Query[Void, DbSreStats] = {
  sql"""
    WITH settings AS (
      SELECT current_setting('max_connections')::bigint AS max_connections
    ),
    conn AS (
      SELECT count(*)::bigint AS total
      FROM pg_stat_activity
    ),
    activity AS (
      SELECT
        count(*) FILTER (
          WHERE state = 'active'
            AND query_start IS NOT NULL
            AND now() - query_start > INTERVAL '30 seconds'
        )::bigint AS long_running_queries,

        count(*) FILTER (
          WHERE state = 'idle in transaction'
            AND state_change IS NOT NULL
            AND now() - state_change > INTERVAL '60 seconds'
        )::bigint AS old_idle_in_txn
      FROM pg_stat_activity
      WHERE datname = current_database()
    ),
    prepared AS (
      SELECT count(*)::bigint AS prepared_transactions
      FROM pg_prepared_xacts
      WHERE database = current_database()
    ),
    invalid_idx AS (
      SELECT count(*) FILTER (
        WHERE NOT i.indisvalid OR NOT i.indisready
      )::bigint AS invalid_indexes
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
    ),
    missing_pk AS (
      SELECT count(*)::bigint AS tables_without_primary_key
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE c.relkind IN ('r', 'p')
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
        AND NOT EXISTS (
          SELECT 1
          FROM pg_index i
          WHERE i.indrelid = c.oid
            AND i.indisprimary
        )
    ),
    vac AS (
      SELECT
        count(*) FILTER (
          WHERE n_dead_tup > 10000
            AND n_dead_tup::numeric / GREATEST(n_live_tup + n_dead_tup, 1) > 0.20
        )::bigint AS tables_need_vacuum,

        count(*) FILTER (
          WHERE n_mod_since_analyze > 10000
            AND n_mod_since_analyze::numeric / GREATEST(n_live_tup + n_mod_since_analyze, 1) > 0.10
        )::bigint AS tables_need_analyze,

        COALESCE(
          max(EXTRACT(EPOCH FROM now() - last_autovacuum))
            FILTER (WHERE last_autovacuum IS NOT NULL),
          0
        )::bigint AS max_autovacuum_age_seconds,

        COALESCE(
          max(EXTRACT(EPOCH FROM now() - last_autoanalyze))
            FILTER (WHERE last_autoanalyze IS NOT NULL),
          0
        )::bigint AS max_autoanalyze_age_seconds
      FROM pg_stat_user_tables
    ),
    freeze_age AS (
      SELECT COALESCE(max(age(c.relfrozenxid)), 0)::bigint AS max_freeze_age
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE c.relkind IN ('r', 't', 'm')
        AND n.nspname NOT IN ('pg_catalog', 'information_schema')
    ),
    slots AS (
      SELECT
        count(*) FILTER (WHERE NOT active)::bigint AS inactive_replication_slots,
        COALESCE(
          max(
            CASE
              WHEN restart_lsn IS NULL THEN 0
              ELSE pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)
            END
          ),
          0
        )::bigint AS max_replication_slot_retained_bytes
      FROM pg_replication_slots
    ),
    wal AS (
      SELECT
        COALESCE(wal_records, 0)::bigint AS wal_records,
        COALESCE(wal_fpi, 0)::bigint AS wal_fpi,
        COALESCE(wal_bytes, 0)::bigint AS wal_bytes
      FROM pg_stat_wal
    )
    SELECT
      settings.max_connections,
      conn.total::float8 / GREATEST(settings.max_connections, 1)::float8,
      activity.long_running_queries,
      activity.old_idle_in_txn,
      prepared.prepared_transactions,
      invalid_idx.invalid_indexes,
      missing_pk.tables_without_primary_key,
      vac.tables_need_vacuum,
      vac.tables_need_analyze,
      vac.max_autovacuum_age_seconds,
      vac.max_autoanalyze_age_seconds,
      freeze_age.max_freeze_age,
      slots.inactive_replication_slots,
      slots.max_replication_slot_retained_bytes,
      wal.wal_records,
      wal.wal_fpi,
      wal.wal_bytes
    FROM settings, conn, activity, prepared, invalid_idx, missing_pk, vac, freeze_age, slots, wal
  """
    .query(
      int8 *: float8 *:
        int8 *: int8 *: int8 *: int8 *: int8 *:
        int8 *: int8 *: int8 *: int8 *: int8 *:
        int8 *: int8 *:
        int8 *: int8 *: int8
    )
    .to[DbSreStats]
}
