-- =============================================================================
-- demo_seed.sql — demo data for a local kudi database (NOT a migration)
-- =============================================================================
-- Loads ~10k users with wallets, transactions, ledger entries, notifications
-- and audit rows spread over 2025-01-01 .. +460 days. Never run in production.
--
--   psql -d kudi -v ON_ERROR_STOP=1 -f kudi/sql/demo_seed.sql
-- =============================================================================

BEGIN;

-- Seed rows are back-dated, so make sure partitions exist from 2025 onwards.
SELECT maintenance.create_partitions(parent, '2025-01-01 00:00+00')
FROM maintenance.partition_config;

-- Users
INSERT INTO users (email, phone, first_name, last_name, country_code, kyc_status_id, password_hash, risk_score, date_of_birth, mfa_enabled)
SELECT
    'user' || g || '@wallet.io',
    '+234' || LPAD((8000000 + g)::TEXT, 10, '0'),
    (ARRAY['Adebayo','Chinwe','Kwame','Fatima','Oluwaseun','Amara','Kofi','Zainab','Emeka','Ngozi'])[floor(random()*10+1)::INT],
    (ARRAY['Okafor','Adeyemi','Mensah','Ibrahim','Ogunleye','Diallo','Asante','Mohammed','Nwosu','Eze'])[floor(random()*10+1)::INT],
    (ARRAY['NG','GH','KE','ZA','US','GB','SG'])[floor(random()*7+1)::INT],
    (ARRAY[1,2,4,4,4,4])[floor(random()*6+1)::INT],   -- mostly verified
    crypt('wallet_pass_' || g, gen_salt('bf', 8)),
    (random() * 300)::INT,
    '1970-01-01'::DATE + (random() * 18000)::INT,
    random() < 0.6
FROM generate_series(1, 10000) g;

-- Wallets (1–3 per user, multi-currency)
INSERT INTO wallets (user_id, balance, hold_amount, lifetime_in, lifetime_out, currency_code, wallet_type_id, status_id, is_default, daily_limit, monthly_limit)
SELECT
    u.user_id,
    round((random() * 500000 + 1000)::NUMERIC, 4),   -- at least 1000, so the hold below always fits
    round((random() * 1000)::NUMERIC, 4),
    round((random() * 2000000)::NUMERIC, 4),
    round((random() * 1500000)::NUMERIC, 4),
    c.currency_code,
    1,   -- personal
    CASE WHEN u.kyc_status_id = 4 THEN 2 ELSE 1 END,   -- active if KYC verified
    c.rn = 1,
    CASE WHEN c.currency_code = 'USD' THEN 5000 WHEN c.currency_code = 'NGN' THEN 5000000 ELSE 5000 END,
    CASE WHEN c.currency_code = 'USD' THEN 50000 WHEN c.currency_code = 'NGN' THEN 50000000 ELSE 50000 END
FROM users u
CROSS JOIN LATERAL (
    SELECT currency_code, ROW_NUMBER() OVER () AS rn
    FROM (
        SELECT unnest(ARRAY['NGN','USD','GBP']) AS currency_code
        ORDER BY random()
        LIMIT 1 + (random()*2)::INT
    ) sub
) c;

-- System fee wallet
INSERT INTO wallets (user_id, balance, currency_code, wallet_type_id, status_id, is_default)
SELECT (SELECT min(user_id) FROM users), 0, currency_code, 4, 2, FALSE
FROM currencies WHERE is_active AND currency_code IN ('NGN','USD','GBP');

-- Payment methods
INSERT INTO payment_methods (user_id, type_id, status_id, currency_code, provider, label, token, last_four, bank_name, is_default)
SELECT
    u.user_id,
    (ARRAY[1,1,2,2,3])[floor(random()*5+1)::INT],
    2,   -- verified
    (ARRAY['NGN','USD','GBP'])[floor(random()*3+1)::INT],
    (ARRAY['paystack','flutterwave','stripe'])[floor(random()*3+1)::INT],
    'Account ****' || LPAD((random()*9999)::INT::TEXT, 4, '0'),
    'tok_' || uuidv7(),
    LPAD((random()*9999)::INT::TEXT, 4, '0'),
    (ARRAY['GTBank','Access Bank','First Bank','UBA','Zenith Bank',NULL,NULL])[floor(random()*7+1)::INT],
    TRUE
FROM users u
WHERE u.kyc_status_id = 4
  AND random() < 0.7;

-- Merchants
INSERT INTO merchants (name, category, fee_rate_bps, api_key_hash, is_verified, webhook_url)
SELECT
    (ARRAY['QuickMart','PayElectric','RideNow','FoodDash','SchoolPay','AirTop','DrugStore','StreamFlix'])[g],
    (ARRAY['groceries','utilities','transport','food','education','airtime','health','entertainment'])[g],
    (ARRAY[150, 100, 200, 175, 120, 80, 150, 180])[g],
    crypt('merchant_key_' || g, gen_salt('bf', 4)),
    TRUE,
    'https://merchant' || g || '.example.com/webhook'
FROM generate_series(1, 8) g;

-- Transactions
INSERT INTO transactions (
    source_wallet_id, dest_wallet_id, amount, fee_amount,
    txn_type_id, status_id, source_currency,
    reference_id, description, created_at, is_suspicious, ip_address
)
SELECT
    w.wallet_id,
    NULL,
    round((random() * 50000 + 100)::NUMERIC, 4),
    round((random() * 100)::NUMERIC, 4),
    (ARRAY[1,2,3,5,7])[floor(random()*5+1)::INT],                              -- top_up, withdrawal, p2p_send, payment, fee
    (ARRAY[3,3,3,3,1,4])[floor(random()*6+1)::INT],                            -- mostly completed
    w.currency_code,
    'TXN-' || uuidv7(),
    'Wallet transaction',
    '2025-01-01'::TIMESTAMPTZ + random() * 460 * INTERVAL '1 day',
    random() < 0.005,
    ('10.' || (random()*255)::INT || '.' || (random()*255)::INT || '.' || (random()*255)::INT)::INET
FROM wallets w
CROSS JOIN LATERAL generate_series(1, 10 + (random()*30)::INT) AS g
WHERE w.wallet_type_id = 1;

-- Every seeded transaction's key is claimed, as the application would.
INSERT INTO transaction_references (reference_id, txn_id, txn_created_at, claimed_at)
SELECT reference_id, txn_id, created_at, created_at FROM transactions;

-- Ledger entries (2 per transaction: debit source, credit dest or system)
INSERT INTO ledger_entries (txn_id, txn_created_at, wallet_id, amount, balance_after, entry_type_id, currency_code, created_at)
SELECT
    t.txn_id,
    t.created_at,
    t.source_wallet_id,
    -t.amount,                                      -- debit
    round((random() * 500000)::NUMERIC, 4),
    1,                                               -- debit type
    t.source_currency,
    t.created_at
FROM transactions t
WHERE t.source_wallet_id IS NOT NULL AND t.status_id = 3;

-- Notifications
INSERT INTO notifications (user_id, channel, title, body, created_at, is_read)
SELECT
    u.user_id,
    (ARRAY['push','sms','email','in_app'])[floor(random()*4+1)::INT],
    (ARRAY['Transfer Successful','Top-Up Complete','Payment Received','Security Alert','Low Balance'])[floor(random()*5+1)::INT],
    'Your wallet has been updated.',
    '2025-01-01'::TIMESTAMPTZ + random() * 460 * INTERVAL '1 day',
    random() < 0.7
FROM users u
CROSS JOIN LATERAL generate_series(1, 3 + (random()*10)::INT) AS g;

-- Audit log
INSERT INTO audit_log (actor_id, action, entity_type, entity_id, ip_address, new_values, created_at)
SELECT
    u.user_id,
    (ARRAY['wallet.create','wallet.top_up','p2p.send','kyc.verify','login.success','pin.change','mfa.enable'])[floor(random()*7+1)::INT],
    'user',
    u.user_id::TEXT,
    ('10.' || (random()*255)::INT || '.' || (random()*255)::INT || '.' || (random()*255)::INT)::INET,
    jsonb_build_object('status', 'ok'),
    '2025-01-01'::TIMESTAMPTZ + random() * 460 * INTERVAL '1 day'
FROM users u
CROSS JOIN LATERAL generate_series(1, 5 + (random()*10)::INT) AS g;

-- Exchange rates (daily snapshots for 460 days)
INSERT INTO exchange_rates (base_currency, quote_currency, rate, inverse_rate, spread_bps, captured_at, source)
SELECT
    'USD', 'NGN',
    round((1500 + random() * 200)::NUMERIC, 4),
    round((1.0 / (1500 + random() * 200))::NUMERIC, 8),
    50,
    '2025-01-01'::TIMESTAMPTZ + g * INTERVAL '1 day',
    'ecb'
FROM generate_series(0, 460) g;

INSERT INTO exchange_rates (base_currency, quote_currency, rate, inverse_rate, spread_bps, captured_at, source)
SELECT
    'USD', 'GBP',
    round((0.78 + random() * 0.05)::NUMERIC, 4),
    round((1.0 / (0.78 + random() * 0.05))::NUMERIC, 8),
    30,
    '2025-01-01'::TIMESTAMPTZ + g * INTERVAL '1 day',
    'ecb'
FROM generate_series(0, 460) g;

-- Fee schedule
INSERT INTO fee_schedule (fee_type_id, txn_type_id, currency_code, flat_fee, percentage_bps, min_fee, max_fee, description)
VALUES
    (1, 1, 'NGN', 0,      100,  50,     5000,   'Top-up: 1%, min ₦50, max ₦5,000'),
    (2, 2, 'NGN', 50,     50,   50,     10000,  'Withdrawal: ₦50 + 0.5%, max ₦10,000'),
    (3, 3, 'NGN', 0,      0,    0,      0,      'P2P: free'),
    (3, 3, 'USD', 0,      25,   0.25,   5,      'P2P USD: 0.25%, min $0.25, max $5'),
    (4, 10,'NGN', 0,      150,  100,    NULL,   'FX: 1.5%, min ₦100'),
    (5, 5, 'NGN', 0,      150,  100,    NULL,   'Payment: 1.5%, min ₦100');

COMMIT;

ANALYZE;
