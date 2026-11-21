-- =============================================================================
-- queries.sql — example analytics and monitoring queries for kudi
-- =============================================================================
-- Reference only; not a migration. Run individual queries with psql.
-- =============================================================================

-- =============================================================================
-- Q1. WALLET STATEMENT  — Paginated using keyset pagination
-- =============================================================================

SELECT
    le.entry_id,
    le.amount,
    le.balance_after,
    let.code          AS entry_type,
    le.currency_code,
    le.description,
    le.created_at
FROM ledger_entries le
JOIN ledger_entry_types let ON let.type_id = le.entry_type_id
WHERE le.wallet_id = 42
  AND le.created_at >= '2025-06-01'
  AND le.created_at <  '2025-07-01'
ORDER BY le.created_at DESC
LIMIT 20;


-- =============================================================================
-- Q2. DAILY WALLET BALANCE RECONSTRUCTION
-- =============================================================================

WITH daily AS (
    SELECT
        wallet_id,
        created_at::DATE AS day,
        SUM(amount) AS net_movement,
        COUNT(*) AS entry_count
    FROM ledger_entries
    WHERE wallet_id = 42
      AND created_at >= '2025-01-01'
      AND created_at <  '2026-01-01'
    GROUP BY wallet_id, created_at::DATE
)
SELECT
    day,
    net_movement,
    entry_count,
    SUM(net_movement) OVER (ORDER BY day) AS running_balance,
    AVG(net_movement) OVER (ORDER BY day ROWS BETWEEN 29 PRECEDING AND CURRENT ROW) AS avg_30d
FROM daily
ORDER BY day;


-- =============================================================================
-- Q3. DOUBLE-ENTRY INTEGRITY CHECK  — Sum of all entries per txn must = 0
-- =============================================================================

SELECT
    txn_id,
    txn_created_at,
    SUM(amount) AS imbalance,
    COUNT(*)    AS entry_count
FROM ledger_entries
WHERE created_at >= now() - INTERVAL '30 days'
GROUP BY txn_id, txn_created_at
HAVING SUM(amount) != 0
ORDER BY ABS(SUM(amount)) DESC;


-- =============================================================================
-- Q4. FRAUD VELOCITY CHECK  — Rapid-fire transactions per wallet
-- =============================================================================

WITH enriched AS (
    SELECT
        t.txn_id,
        t.source_wallet_id,
        t.amount,
        t.created_at,
        tt.code AS txn_type,

        t.created_at - LAG(t.created_at) OVER w AS time_since_last,

        COUNT(*) OVER (
            PARTITION BY t.source_wallet_id
            ORDER BY t.created_at
            RANGE BETWEEN '1 hour'::INTERVAL PRECEDING AND CURRENT ROW
        ) AS txns_last_hour,

        SUM(t.amount) OVER (
            PARTITION BY t.source_wallet_id
            ORDER BY t.created_at
            RANGE BETWEEN '24 hours'::INTERVAL PRECEDING AND CURRENT ROW
        ) AS spend_24h,

        AVG(t.amount) OVER w30 AS avg_amount_30d,
        STDDEV(t.amount) OVER w30 AS stddev_amount_30d

    FROM transactions t
    JOIN txn_types tt ON tt.type_id = t.txn_type_id
    WHERE t.created_at >= now() - INTERVAL '90 days'
      AND t.source_wallet_id IS NOT NULL
    WINDOW
        w   AS (PARTITION BY t.source_wallet_id ORDER BY t.created_at),
        w30 AS (PARTITION BY t.source_wallet_id ORDER BY t.created_at ROWS BETWEEN 29 PRECEDING AND CURRENT ROW)
)
SELECT
    txn_id,
    source_wallet_id,
    amount,
    txn_type,
    time_since_last,
    txns_last_hour,
    spend_24h,
    ROUND(avg_amount_30d, 2) AS avg_30d,
    CASE
        WHEN txns_last_hour > 10                                THEN 'VELOCITY_SPIKE'
        WHEN time_since_last < INTERVAL '30 seconds'            THEN 'RAPID_FIRE'
        WHEN stddev_amount_30d > 0
             AND amount > avg_amount_30d + 3 * stddev_amount_30d THEN 'AMOUNT_ANOMALY'
        WHEN spend_24h > 100000                                  THEN 'DAILY_LIMIT_RISK'
    END AS risk_flag
FROM enriched
WHERE txns_last_hour > 5
   OR time_since_last < INTERVAL '1 minute'
   OR (stddev_amount_30d > 0 AND amount > avg_amount_30d + 2 * stddev_amount_30d)
ORDER BY created_at DESC;


-- =============================================================================
-- Q5. REVENUE DASHBOARD  — Fee revenue by type and month
-- =============================================================================

WITH monthly AS (
    SELECT
        DATE_TRUNC('month', t.created_at)::DATE AS month,
        ft.code AS fee_type,
        SUM(t.fee_amount) AS fee_revenue,
        COUNT(*) AS txn_count
    FROM transactions t
    JOIN txn_types tt ON tt.type_id = t.txn_type_id
    JOIN fee_types ft ON ft.code = tt.code || '_fee'
    WHERE t.status_id = 3
      AND t.fee_amount > 0
    GROUP BY DATE_TRUNC('month', t.created_at), ft.code
)
SELECT
    month,
    fee_type,
    fee_revenue,
    txn_count,
    LAG(fee_revenue) OVER w AS prev_month,
    ROUND(100.0 * (fee_revenue - LAG(fee_revenue) OVER w)
          / NULLIF(LAG(fee_revenue) OVER w, 0), 1) AS growth_pct,
    SUM(fee_revenue) OVER (PARTITION BY fee_type ORDER BY month) AS cumulative
FROM monthly
WINDOW w AS (PARTITION BY fee_type ORDER BY month)
ORDER BY month, fee_type;


-- =============================================================================
-- Q6. WALLET-TO-WALLET TRANSFER with MERGE ... RETURNING (PG18)
-- =============================================================================
-- Atomic balance update: debit source wallet, capture the result.
-- In a real app this runs inside a transaction with the credit MERGE.
-- =============================================================================

-- Step 1: debit source
/*
MERGE INTO wallets AS w
USING (VALUES (42, 1000.0000)) AS input(wallet_id, amount)
ON w.wallet_id = input.wallet_id
WHEN MATCHED AND w.balance >= input.amount AND w.status_id = 2 THEN
    UPDATE SET
        balance      = w.balance - input.amount,
        hold_amount  = w.hold_amount,
        lifetime_out = w.lifetime_out + input.amount
RETURNING w.wallet_id, w.balance, w.available_balance, 'debited' AS action;
*/

-- Step 2: credit destination
/*
MERGE INTO wallets AS w
USING (VALUES (99, 1000.0000)) AS input(wallet_id, amount)
ON w.wallet_id = input.wallet_id
WHEN MATCHED AND w.status_id = 2 THEN
    UPDATE SET
        balance     = w.balance + input.amount,
        lifetime_in = w.lifetime_in + input.amount
RETURNING w.wallet_id, w.balance, w.available_balance, 'credited' AS action;
*/


-- =============================================================================
-- Q7. USER COHORT RETENTION  — Monthly signup cohorts
-- =============================================================================

WITH user_months AS (
    SELECT DISTINCT
        w.user_id,
        DATE_TRUNC('month', t.created_at)::DATE AS active_month
    FROM transactions t
    JOIN wallets w ON w.wallet_id = t.source_wallet_id
    WHERE t.status_id = 3
),
cohorted AS (
    SELECT
        user_id,
        active_month,
        FIRST_VALUE(active_month) OVER (
            PARTITION BY user_id ORDER BY active_month
        ) AS cohort_month
    FROM user_months
)
SELECT
    cohort_month,
    active_month,
    (EXTRACT(YEAR FROM age(active_month, cohort_month)) * 12 +
     EXTRACT(MONTH FROM age(active_month, cohort_month)))::INT AS months_since_join,
    COUNT(DISTINCT user_id) AS active_users
FROM cohorted
GROUP BY cohort_month, active_month
ORDER BY cohort_month, active_month;


-- =============================================================================
-- Q8. TOP-UP FUNNEL  — Where do users drop off?
-- =============================================================================

WITH funnel AS (
    SELECT
        u.user_id,
        u.created_at                                                        AS step1_signup,
        CASE WHEN u.kyc_status_id >= 2 THEN u.created_at END               AS step2_kyc_started,
        CASE WHEN u.kyc_status_id = 4  THEN u.updated_at END               AS step3_kyc_verified,
        (SELECT MIN(w.created_at) FROM wallets w
         WHERE w.user_id = u.user_id)                                       AS step4_wallet_created,
        (SELECT MIN(pm.created_at) FROM payment_methods pm
         WHERE pm.user_id = u.user_id AND pm.status_id = 2)                AS step5_pm_linked,
        (SELECT MIN(t.created_at) FROM transactions t
         JOIN wallets w ON w.wallet_id = t.dest_wallet_id
         WHERE w.user_id = u.user_id AND t.txn_type_id = 1)                AS step6_first_top_up
    FROM users u
    WHERE u.created_at >= now() - INTERVAL '6 months'
)
SELECT
    COUNT(*)                     AS signups,
    COUNT(step2_kyc_started)     AS kyc_started,
    COUNT(step3_kyc_verified)    AS kyc_verified,
    COUNT(step4_wallet_created)  AS wallet_created,
    COUNT(step5_pm_linked)       AS pm_linked,
    COUNT(step6_first_top_up)    AS first_top_up,
    ROUND(100.0 * COUNT(step6_first_top_up) / NULLIF(COUNT(*), 0), 1) AS overall_conversion_pct
FROM funnel;


-- =============================================================================
-- Q9. FX RATE TREND  — 7-day and 30-day moving averages
-- =============================================================================

SELECT
    captured_at::DATE AS day,
    rate,
    ROUND(AVG(rate) OVER (ORDER BY captured_at::DATE ROWS BETWEEN 6  PRECEDING AND CURRENT ROW), 4) AS sma_7d,
    ROUND(AVG(rate) OVER (ORDER BY captured_at::DATE ROWS BETWEEN 29 PRECEDING AND CURRENT ROW), 4) AS sma_30d,
    ROUND(rate - LAG(rate) OVER (ORDER BY captured_at::DATE), 4) AS daily_change,
    ROUND(100.0 * (rate - LAG(rate) OVER (ORDER BY captured_at::DATE))
          / NULLIF(LAG(rate) OVER (ORDER BY captured_at::DATE), 0), 2) AS daily_change_pct
FROM exchange_rates
WHERE base_currency = 'USD'
  AND quote_currency = 'NGN'
  AND captured_at >= now() - INTERVAL '120 days'
ORDER BY day;


-- =============================================================================
-- Q10. MERCHANT SETTLEMENT SUMMARY  — Revenue per merchant
-- =============================================================================

SELECT
    m.merchant_id,
    m.name,
    m.category,
    COUNT(*)                    AS txn_count,
    SUM(t.amount)               AS gross_volume,
    SUM(t.fee_amount)           AS total_fees,
    SUM(t.amount - t.fee_amount) AS net_settlement,
    ROUND(AVG(t.amount), 2)     AS avg_ticket,
    ROUND(100.0 * SUM(t.fee_amount) / NULLIF(SUM(t.amount), 0), 3) AS effective_fee_pct
FROM transactions t
JOIN merchants m ON m.merchant_id = t.merchant_id
WHERE t.status_id = 3
  AND t.created_at >= DATE_TRUNC('month', now())
GROUP BY m.merchant_id, m.name, m.category
ORDER BY gross_volume DESC;


-- =============================================================================
-- Q11. DAILY OPERATIONAL DASHBOARD  — Moving averages + anomalies
-- =============================================================================

WITH daily AS (
    SELECT
        created_at::DATE AS day,
        COUNT(*) AS txn_count,
        SUM(amount) AS volume,
        SUM(fee_amount) AS fees,
        COUNT(DISTINCT source_wallet_id) AS active_wallets,
        COUNT(*) FILTER (WHERE is_suspicious) AS suspicious_count,
        COUNT(*) FILTER (WHERE status_id = 4) AS failed_count
    FROM transactions
    WHERE created_at >= now() - INTERVAL '120 days'
    GROUP BY created_at::DATE
)
SELECT
    day,
    txn_count,
    volume,
    fees,
    active_wallets,
    suspicious_count,
    failed_count,
    ROUND(AVG(txn_count) OVER w7, 0)  AS txn_7d_avg,
    ROUND(AVG(volume)    OVER w7, 2)  AS vol_7d_avg,
    ROUND(AVG(txn_count) OVER w30, 0) AS txn_30d_avg,
    ROUND(AVG(volume)    OVER w30, 2) AS vol_30d_avg,
    txn_count - LAG(txn_count) OVER (ORDER BY day) AS txn_dod
FROM daily
WINDOW
    w7  AS (ORDER BY day ROWS BETWEEN 6  PRECEDING AND CURRENT ROW),
    w30 AS (ORDER BY day ROWS BETWEEN 29 PRECEDING AND CURRENT ROW)
ORDER BY day;


-- =============================================================================
-- =============================================================================
--
--  M O N I T O R I N G
--
-- =============================================================================
-- =============================================================================


-- M1. Ledger imbalance check (should return 0 rows)
SELECT txn_id, txn_created_at, SUM(amount) AS imbalance
FROM ledger_entries
WHERE created_at >= now() - INTERVAL '24 hours'
GROUP BY txn_id, txn_created_at
HAVING SUM(amount) != 0;

-- M2. Wallet balance vs ledger reconciliation
SELECT
    w.wallet_id,
    w.balance AS wallet_balance,
    COALESCE(SUM(le.amount), 0) AS ledger_balance,
    w.balance - COALESCE(SUM(le.amount), 0) AS drift
FROM wallets w
LEFT JOIN ledger_entries le ON le.wallet_id = w.wallet_id
GROUP BY w.wallet_id, w.balance
HAVING w.balance != COALESCE(SUM(le.amount), 0)
LIMIT 50;

-- M3. Stuck pending transactions (> 15 min old)
SELECT txn_id, source_wallet_id, amount, created_at, age(now(), created_at) AS age
FROM transactions
WHERE status_id = 1
  AND created_at < now() - INTERVAL '15 minutes'
ORDER BY created_at
LIMIT 50;

-- M4. Cache hit ratio
SELECT ROUND(100.0 * SUM(blks_hit) / NULLIF(SUM(blks_hit + blks_read), 0), 2) AS cache_hit_pct
FROM pg_stat_database WHERE datname = current_database();

-- M5. Table bloat
SELECT relname, n_live_tup, n_dead_tup,
       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       last_autovacuum
FROM pg_stat_user_tables WHERE n_dead_tup > 1000 ORDER BY n_dead_tup DESC;

-- M6. Partition sizes
SELECT i.inhparent::regclass AS parent,
       c.relname AS partition,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
       s.n_live_tup
FROM pg_inherits i
JOIN pg_class c ON c.oid = i.inhrelid
JOIN pg_stat_user_tables s ON s.relid = c.oid
WHERE i.inhparent IN (SELECT parent FROM maintenance.partition_config)
ORDER BY 1, 2;

-- M6b. Partition runway: alert when runway drops under ~30 days
SELECT * FROM maintenance.v_partition_runway ORDER BY runway;

-- M7. Slow queries
SELECT LEFT(query, 120) AS query_preview, calls,
       ROUND(mean_exec_time::NUMERIC, 1) AS avg_ms,
       rows
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY mean_exec_time DESC LIMIT 20;
