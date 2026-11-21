-- =============================================================================
-- repack_bloated.sql — reclaim table bloat online (PostgreSQL 19)
-- =============================================================================
-- REPACK (CONCURRENTLY) rewrites a table without blocking reads or writes; it
-- only takes a brief ACCESS EXCLUSIVE lock to swap the files at the end. It
-- replaces VACUUM FULL / CLUSTER (which block the table for the whole rewrite)
-- and the pg_repack extension.
--
-- Run against any service database, outside a transaction:
--   psql -d kudi -v ON_ERROR_STOP=1 -f deploy/sql/repack_bloated.sql
--   psql -d kudi -v min_dead_pct=30 -v min_size_mb=256 -f deploy/sql/repack_bloated.sql
--
-- Caveats (from the PostgreSQL 19 REPACK documentation):
--   • Needs a primary key or index-based replica identity, a free slot in
--     max_repack_replication_slots (default 5), and roughly the table's size
--     in free disk space.
--   • Not MVCC-safe: a transaction whose snapshot predates the repack sees
--     the table as empty afterwards. Run it when no long reports are open.
--   • Partitioned parents are skipped; their leaf partitions are listed
--     individually.
-- =============================================================================

\set ON_ERROR_STOP on
\if :{?min_dead_pct}
\else
  \set min_dead_pct 20
\endif
\if :{?min_size_mb}
\else
  \set min_size_mb 64
\endif

-- Candidates: big enough to matter, mostly dead, eligible for CONCURRENTLY.
SELECT format('REPACK (CONCURRENTLY, VERBOSE) %I.%I', s.schemaname, s.relname)
FROM pg_stat_user_tables s
JOIN pg_class c ON c.oid = s.relid
WHERE c.relkind = 'r'
  AND c.relpersistence = 'p'                                   -- not UNLOGGED
  AND pg_table_size(s.relid) >= :min_size_mb * 1024 * 1024
  AND s.n_dead_tup * 100.0 / nullif(s.n_live_tup + s.n_dead_tup, 0) >= :min_dead_pct
  AND (EXISTS (SELECT FROM pg_index i WHERE i.indrelid = s.relid AND i.indisprimary)
       OR c.relreplident = 'i')
  AND NOT EXISTS (SELECT FROM pg_index i WHERE i.indrelid = s.relid AND NOT i.indisvalid)
ORDER BY s.n_dead_tup DESC
\gexec
