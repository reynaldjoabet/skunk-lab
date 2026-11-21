-- =============================================================================
-- V1__init.sql — kudi digital wallet schema
-- =============================================================================
--
--   • Double-entry ledger: every money movement = 2 entries that sum to zero
--   • Multi-currency wallets with exchange rate tracking
--   • Authentication: credentials, sessions, MFA, refresh tokens, API keys
--   • Authorization: RBAC with roles, permissions, user-role assignments
--   • KYC document management with review workflow
--   • Notifications: templates, per-user preferences, delivery tracking
--   • Audit: immutable append-only log (UPDATE/DELETE blocked by trigger)
--   • Row-Level Security: users can only access their own data
--   • Lookup tables instead of enums: statuses are rows, evolvable in a transaction
--   • Column ordering: 8-byte → 4-byte → 2-byte → 1-byte → variable
--   • Partitioned: transactions and ledger_entries by month, audit_log by
--     quarter; partitions are created ahead of time by
--     maintenance.run_partition_maintenance() (see PARTITION MAINTENANCE)
--   • Idempotency: transaction_references claims each reference_id exactly once
--
-- Schema only. Demo data lives in kudi/sql/demo_seed.sql and the example and
-- monitoring queries in kudi/sql/queries.sql. Neither belongs here: every file
-- in this directory runs when a new database is initialised.
--
-- Target: PostgreSQL 19. Relies on:
--   • INSERT ... ON CONFLICT DO SELECT (19) — atomic claim of an idempotency key
--   • RETURNING old.* / new.* (18)          — tells a new claim from a replay
--   • Virtual generated columns (18)        — users.full_name, wallets.available_balance
--   • NOT ENFORCED constraints (18)         — documentary-only checks
--   • autovacuum_parallel_workers (19)      — parallel index vacuum on hot partitions
-- =============================================================================


-- =============================================================================
-- EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;


-- =============================================================================
-- SCHEMAS  — Logical separation for least-privilege access per service/role
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS auth;         -- credentials, sessions, MFA, API keys
CREATE SCHEMA IF NOT EXISTS core;         -- roles, permissions (RBAC)
CREATE SCHEMA IF NOT EXISTS notify;       -- notification delivery, preferences
CREATE SCHEMA IF NOT EXISTS audit;        -- immutable audit trail
CREATE SCHEMA IF NOT EXISTS maintenance;  -- partition upkeep; owner-only


-- =============================================================================
-- DOMAINS
-- =============================================================================

-- ISO 4217 codes are three letters, but the platform also carries crypto and
-- stablecoin codes such as USDT, which a fixed-width 3-character column cannot
-- hold. One domain defines a valid code for every column that stores one.
CREATE DOMAIN currency_code AS TEXT
    CHECK (VALUE ~ '^[A-Z][A-Z0-9]{2,4}$');


-- =============================================================================
-- =============================================================================
--
--  L O O K U P   T A B L E S
--
--  No enums. Every status/type is a row you can INSERT in a transaction.
--  SMALLINT PK = 2 bytes per FK — same as enum OID cost, fully evolvable.
--
-- =============================================================================
-- =============================================================================


CREATE TABLE kyc_statuses (
    status_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_terminal  BOOL        NOT NULL DEFAULT FALSE,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO kyc_statuses (status_id, code, display_name, is_terminal, sort_order) VALUES
    (1, 'not_started', 'Not Started',  FALSE, 1),
    (2, 'pending',     'Pending',      FALSE, 2),
    (3, 'in_review',   'In Review',    FALSE, 3),
    (4, 'verified',    'Verified',     TRUE,  4),
    (5, 'rejected',    'Rejected',     TRUE,  5),
    (6, 'expired',     'Expired',      TRUE,  6);


CREATE TABLE wallet_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO wallet_types (type_id, code, display_name, sort_order) VALUES
    (1, 'personal',   'Personal',    1),
    (2, 'business',   'Business',    2),
    (3, 'escrow',     'Escrow',      3),
    (4, 'fee_collect', 'Fee Collection', 4),    -- system wallet for collecting fees
    (5, 'settlement', 'Settlement',  5);         -- system wallet for merchant payouts


CREATE TABLE wallet_statuses (
    status_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_terminal  BOOL        NOT NULL DEFAULT FALSE,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO wallet_statuses (status_id, code, display_name, is_terminal, sort_order) VALUES
    (1, 'pending_kyc', 'Pending KYC',  FALSE, 1),
    (2, 'active',      'Active',       FALSE, 2),
    (3, 'frozen',      'Frozen',       FALSE, 3),
    (4, 'suspended',   'Suspended',    FALSE, 4),
    (5, 'closed',      'Closed',       TRUE,  5);


CREATE TABLE txn_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    direction    SMALLINT    NOT NULL,           -- 1 = credit, -1 = debit, 0 = internal
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO txn_types (type_id, code, display_name, direction, sort_order) VALUES
    (1,  'top_up',          'Top Up',             1,  1),
    (2,  'withdrawal',      'Withdrawal',        -1,  2),
    (3,  'p2p_send',        'P2P Send',          -1,  3),
    (4,  'p2p_receive',     'P2P Receive',        1,  4),
    (5,  'payment',         'Payment',           -1,  5),
    (6,  'refund',          'Refund',             1,  6),
    (7,  'fee',             'Fee',               -1,  7),
    (8,  'cashback',        'Cashback',           1,  8),
    (9,  'interest',        'Interest',           1,  9),
    (10, 'fx_buy',          'FX Buy',             1, 10),
    (11, 'fx_sell',         'FX Sell',           -1, 11),
    (12, 'reversal',        'Reversal',           0, 12),
    (13, 'adjustment',      'Adjustment',         0, 13);


CREATE TABLE txn_statuses (
    status_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_terminal  BOOL        NOT NULL DEFAULT FALSE,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO txn_statuses (status_id, code, display_name, is_terminal, sort_order) VALUES
    (1, 'pending',     'Pending',     FALSE, 1),
    (2, 'processing',  'Processing',  FALSE, 2),
    (3, 'completed',   'Completed',   TRUE,  3),
    (4, 'failed',      'Failed',      TRUE,  4),
    (5, 'reversed',    'Reversed',    TRUE,  5),
    (6, 'expired',     'Expired',     TRUE,  6),
    (7, 'on_hold',     'On Hold',     FALSE, 7);


CREATE TABLE payment_method_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO payment_method_types (type_id, code, display_name, sort_order) VALUES
    (1, 'bank_account', 'Bank Account',  1),
    (2, 'debit_card',   'Debit Card',    2),
    (3, 'credit_card',  'Credit Card',   3),
    (4, 'crypto',       'Crypto Wallet', 4),
    (5, 'mobile_money', 'Mobile Money',  5);


CREATE TABLE payment_method_statuses (
    status_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_terminal  BOOL        NOT NULL DEFAULT FALSE,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO payment_method_statuses (status_id, code, display_name, is_terminal, sort_order) VALUES
    (1, 'pending_verification', 'Pending Verification', FALSE, 1),
    (2, 'verified',             'Verified',             FALSE, 2),
    (3, 'failed_verification',  'Failed Verification',  TRUE,  3),
    (4, 'disabled',             'Disabled',             FALSE, 4),
    (5, 'expired',              'Expired',              TRUE,  5),
    (6, 'removed',              'Removed',              TRUE,  6);


CREATE TABLE ledger_entry_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE
);

INSERT INTO ledger_entry_types (type_id, code, display_name) VALUES
    (1, 'debit',  'Debit'),
    (2, 'credit', 'Credit');


CREATE TABLE currencies (
    currency_code   currency_code PRIMARY KEY,  -- ISO 4217, plus crypto codes
    display_name    TEXT        NOT NULL,
    symbol          TEXT        NOT NULL,
    decimal_places  SMALLINT   NOT NULL DEFAULT 2,
    is_active       BOOL       NOT NULL DEFAULT TRUE
);

INSERT INTO currencies (currency_code, display_name, symbol, decimal_places) VALUES
    ('USD', 'US Dollar',          '$',  2),
    ('EUR', 'Euro',               '€',  2),
    ('GBP', 'British Pound',      '£',  2),
    ('NGN', 'Nigerian Naira',     '₦',  2),
    ('KES', 'Kenyan Shilling',    'KSh', 2),
    ('GHS', 'Ghanaian Cedi',      'GH₵', 2),
    ('ZAR', 'South African Rand', 'R',  2),
    ('BTC', 'Bitcoin',            '₿',  8),
    ('USDT','Tether',             '₮',  6);


CREATE TABLE fee_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE
);

INSERT INTO fee_types (type_id, code, display_name) VALUES
    (1, 'top_up_fee',        'Top-Up Fee'),
    (2, 'withdrawal_fee',    'Withdrawal Fee'),
    (3, 'p2p_fee',           'P2P Transfer Fee'),
    (4, 'fx_fee',            'FX Conversion Fee'),
    (5, 'payment_fee',       'Payment Processing Fee'),
    (6, 'monthly_fee',       'Monthly Maintenance Fee'),
    (7, 'dormancy_fee',      'Dormancy Fee');


CREATE TABLE mfa_method_types (
    type_id      SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO mfa_method_types (type_id, code, display_name, sort_order) VALUES
    (1, 'totp',     'TOTP Authenticator', 1),
    (2, 'sms',      'SMS OTP',            2),
    (3, 'email',    'Email OTP',          3),
    (4, 'webauthn', 'WebAuthn / Passkey', 4);


CREATE TABLE notification_channels (
    channel_id   SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO notification_channels (channel_id, code, display_name, sort_order) VALUES
    (1, 'email',   'Email',          1),
    (2, 'sms',     'SMS',            2),
    (3, 'push',    'Push',           3),
    (4, 'in_app',  'In-App',         4),
    (5, 'webhook', 'Webhook',        5);


CREATE TABLE delivery_statuses (
    status_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL,
    is_terminal  BOOL        NOT NULL DEFAULT FALSE,
    is_active    BOOL        NOT NULL DEFAULT TRUE,
    sort_order   SMALLINT    NOT NULL DEFAULT 0
);

INSERT INTO delivery_statuses (status_id, code, display_name, is_terminal, sort_order) VALUES
    (1, 'pending',   'Pending',   FALSE, 1),
    (2, 'sent',      'Sent',      FALSE, 2),
    (3, 'delivered', 'Delivered', TRUE,  3),
    (4, 'failed',    'Failed',    TRUE,  4),
    (5, 'bounced',   'Bounced',   TRUE,  5);


CREATE TABLE session_revoke_reasons (
    reason_id    SMALLINT    PRIMARY KEY,
    code         TEXT        NOT NULL UNIQUE,
    display_name TEXT        NOT NULL
);

INSERT INTO session_revoke_reasons (reason_id, code, display_name) VALUES
    (1, 'logout',              'User Logout'),
    (2, 'password_change',     'Password Changed'),
    (3, 'admin_revoke',        'Admin Revocation'),
    (4, 'expired',             'Session Expired'),
    (5, 'max_sessions',        'Max Sessions Exceeded'),
    (6, 'security_lockout',    'Security Lockout');


-- =============================================================================
-- =============================================================================
--
--  C O R E   T A B L E S
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 1. USERS
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    -- 8-byte aligned
    user_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    last_login_at       TIMESTAMPTZ,
    date_of_birth       DATE,

    -- 4-byte aligned
    risk_score          INT             NOT NULL DEFAULT 0,
    failed_login_count  INT             NOT NULL DEFAULT 0,

    -- 2-byte aligned
    kyc_status_id       SMALLINT        NOT NULL DEFAULT 1 REFERENCES kyc_statuses(status_id),

    -- 1-byte aligned
    is_blocked          BOOL            NOT NULL DEFAULT FALSE,
    is_pep              BOOL            NOT NULL DEFAULT FALSE,
    mfa_enabled         BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    email               CITEXT          NOT NULL UNIQUE,
    phone               TEXT            NOT NULL UNIQUE,
    full_name           TEXT            GENERATED ALWAYS AS (first_name || ' ' || last_name) VIRTUAL,
    first_name          TEXT            NOT NULL,
    last_name           TEXT            NOT NULL,
    country_code        CHAR(2)         NOT NULL,
    password_hash       TEXT            NOT NULL,
    pin_hash            TEXT,
    profile_image_url   TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);

ALTER TABLE users SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.01
);


-- ---------------------------------------------------------------------------
-- 2. WALLETS  — Multi-currency, one wallet per currency per user
-- ---------------------------------------------------------------------------
CREATE TABLE wallets (
    -- 8-byte aligned
    wallet_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id),
    balance             NUMERIC(18,4)   NOT NULL DEFAULT 0,
    hold_amount         NUMERIC(18,4)   NOT NULL DEFAULT 0,
    lifetime_in         NUMERIC(18,4)   NOT NULL DEFAULT 0,
    lifetime_out        NUMERIC(18,4)   NOT NULL DEFAULT 0,
    daily_limit         NUMERIC(18,4)   NOT NULL DEFAULT 5000.0000,
    monthly_limit       NUMERIC(18,4)   NOT NULL DEFAULT 50000.0000,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 2-byte aligned
    wallet_type_id      SMALLINT        NOT NULL DEFAULT 1 REFERENCES wallet_types(type_id),
    status_id           SMALLINT        NOT NULL DEFAULT 1 REFERENCES wallet_statuses(status_id),

    -- 1-byte aligned
    is_default          BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    currency_code       currency_code   NOT NULL REFERENCES currencies(currency_code),
    wallet_tag          TEXT,           -- user-friendly label: "My USD", "Savings"
    metadata            JSONB           NOT NULL DEFAULT '{}',

    -- PG18: available balance computed on read, zero storage
    available_balance   NUMERIC(18,4)   GENERATED ALWAYS AS (balance - hold_amount) VIRTUAL,

    -- One default wallet per currency per user
    UNIQUE (user_id, currency_code, wallet_type_id),

    CONSTRAINT chk_wallet_balance CHECK (balance >= 0),
    CONSTRAINT chk_wallet_hold    CHECK (hold_amount >= 0 AND hold_amount <= balance),

    -- PG18 NOT ENFORCED: daily_limit is enforced by application, documented for analysts
    CONSTRAINT chk_wallet_daily_limit CHECK (daily_limit > 0) NOT ENFORCED
);


-- ---------------------------------------------------------------------------
-- 3. PAYMENT_METHODS  — Linked external accounts for top-ups and withdrawals
-- ---------------------------------------------------------------------------
CREATE TABLE payment_methods (
    -- 8-byte aligned
    method_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    verified_at         TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,

    -- 2-byte aligned
    type_id             SMALLINT        NOT NULL REFERENCES payment_method_types(type_id),
    status_id           SMALLINT        NOT NULL DEFAULT 1 REFERENCES payment_method_statuses(status_id),

    -- 1-byte aligned
    is_default          BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    currency_code       currency_code   NOT NULL REFERENCES currencies(currency_code),
    provider            TEXT            NOT NULL,    -- 'stripe', 'paystack', 'flutterwave'
    label               TEXT,                        -- "GTBank ****4521"
    token               TEXT            NOT NULL,    -- tokenized by payment provider
    last_four           CHAR(4),
    bank_name           TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 4. MERCHANTS  — Payment recipients
-- ---------------------------------------------------------------------------
CREATE TABLE merchants (
    -- 8-byte aligned
    merchant_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_wallet_id BIGINT         REFERENCES wallets(wallet_id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 4-byte aligned
    fee_rate_bps        INT             NOT NULL DEFAULT 150,    -- 1.5% = 150 basis points

    -- 2-byte aligned
    status_id           SMALLINT        NOT NULL DEFAULT 2 REFERENCES wallet_statuses(status_id),

    -- 1-byte aligned
    is_verified         BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    name                TEXT            NOT NULL,
    category            TEXT            NOT NULL,    -- 'food', 'transport', 'utilities'
    api_key_hash        TEXT            NOT NULL,
    webhook_url         TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 5. TRANSACTIONS  — Every money movement (partitioned by month)
-- ---------------------------------------------------------------------------
-- The single source of truth for "what happened." Each transaction may
-- generate 2+ ledger entries (double-entry) and potentially a fee transaction.
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    -- 8-byte aligned
    txn_id              BIGINT GENERATED ALWAYS AS IDENTITY,
    source_wallet_id    BIGINT          REFERENCES wallets(wallet_id),   -- NULL for top-ups from external
    dest_wallet_id      BIGINT          REFERENCES wallets(wallet_id),   -- NULL for withdrawals to external
    amount              NUMERIC(18,4)   NOT NULL,
    fee_amount          NUMERIC(18,4)   NOT NULL DEFAULT 0,
    exchange_rate       NUMERIC(18,8),              -- non-NULL for FX transactions
    balance_after       NUMERIC(18,4),              -- snapshot of source wallet after
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,                -- pending txns auto-expire
    merchant_id         BIGINT          REFERENCES merchants(merchant_id),  -- non-NULL for payments
    related_txn_id      BIGINT,                     -- links refund → original, fee → parent

    -- 2-byte aligned
    txn_type_id         SMALLINT        NOT NULL REFERENCES txn_types(type_id),
    status_id           SMALLINT        NOT NULL DEFAULT 1 REFERENCES txn_statuses(status_id),

    -- 1-byte aligned
    is_suspicious       BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    source_currency     currency_code   REFERENCES currencies(currency_code),
    dest_currency       currency_code   REFERENCES currencies(currency_code),
    -- Client idempotency key. Uniqueness is enforced by transaction_references:
    -- a UNIQUE constraint here would have to include created_at (the partition
    -- key), and would then no longer stop the same key being used twice.
    reference_id        TEXT            NOT NULL,
    description         TEXT,
    failure_reason      TEXT,
    ip_address          INET,
    device_fingerprint  TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}',

    -- Enforced: these guard money, and a CHECK costs next to nothing per row.
    CONSTRAINT chk_txn_positive   CHECK (amount > 0),
    CONSTRAINT chk_txn_fee        CHECK (fee_amount >= 0),
    CONSTRAINT chk_txn_has_wallet CHECK (source_wallet_id IS NOT NULL OR dest_wallet_id IS NOT NULL),

    PRIMARY KEY (txn_id, created_at)
) PARTITION BY RANGE (created_at);

-- Partitions are created by maintenance.run_partition_maintenance() below.
-- Autovacuum settings live in maintenance.partition_config: PostgreSQL rejects
-- storage parameters on a partitioned parent, so they are set per partition.


-- ---------------------------------------------------------------------------
-- 6. LEDGER_ENTRIES  — Double-entry bookkeeping (partitioned)
-- ---------------------------------------------------------------------------
-- Golden rule: for every transaction, SUM(amount) across its entries = 0.
-- A debit is negative, a credit is positive (or vice versa — pick a convention
-- and stick with it). We use: credit = +amount, debit = -amount.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    -- 8-byte aligned
    entry_id            BIGINT GENERATED ALWAYS AS IDENTITY,
    txn_id              BIGINT          NOT NULL,
    txn_created_at      TIMESTAMPTZ     NOT NULL,
    wallet_id           BIGINT          NOT NULL REFERENCES wallets(wallet_id),
    amount              NUMERIC(18,4)   NOT NULL,   -- positive = credit, negative = debit
    balance_after       NUMERIC(18,4)   NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 2-byte aligned
    entry_type_id       SMALLINT        NOT NULL REFERENCES ledger_entry_types(type_id),

    -- variable-length
    currency_code       currency_code   NOT NULL REFERENCES currencies(currency_code),
    description         TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}',

    PRIMARY KEY (entry_id, created_at),

    -- Every entry belongs to a real transaction. PostgreSQL 19 made foreign key
    -- checks considerably cheaper, which matters on the hottest insert path.
    FOREIGN KEY (txn_id, txn_created_at) REFERENCES transactions (txn_id, created_at),

    CONSTRAINT chk_ledger_nonzero CHECK (amount <> 0)
) PARTITION BY RANGE (created_at);


-- ---------------------------------------------------------------------------
-- 6b. TRANSACTION_REFERENCES  — One row per idempotency key
-- ---------------------------------------------------------------------------
-- Not partitioned, so the primary key is global. A request claims its key with
--   INSERT ... ON CONFLICT (reference_id) DO SELECT FOR UPDATE
--   RETURNING ..., old.reference_id IS NULL AS claimed
-- in the same database transaction that moves the money. A concurrent request
-- with the same key waits on the row lock, then sees txn_id set and replays
-- the original result instead of moving the money twice. If the first request
-- rolls back, its claim disappears with it and the waiter claims the key.
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_references (
    reference_id        TEXT            PRIMARY KEY,
    txn_id              BIGINT,
    txn_created_at      TIMESTAMPTZ,
    claimed_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    FOREIGN KEY (txn_id, txn_created_at) REFERENCES transactions (txn_id, created_at),
    CONSTRAINT chk_ref_txn_pair CHECK ((txn_id IS NULL) = (txn_created_at IS NULL)),
    CONSTRAINT chk_ref_length   CHECK (length(reference_id) BETWEEN 1 AND 255)
);


-- ---------------------------------------------------------------------------
-- 7. FEE_SCHEDULE  — Configurable fee rules per transaction type
-- ---------------------------------------------------------------------------
CREATE TABLE fee_schedule (
    -- 8-byte aligned
    fee_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    effective_from      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    effective_to        TIMESTAMPTZ,

    -- 4-byte aligned
    flat_fee            NUMERIC(18,4)   NOT NULL DEFAULT 0,
    percentage_bps      INT             NOT NULL DEFAULT 0,    -- basis points (100 = 1%)
    min_fee             NUMERIC(18,4)   NOT NULL DEFAULT 0,
    max_fee             NUMERIC(18,4),                         -- NULL = no cap
    min_txn_amount      NUMERIC(18,4)   NOT NULL DEFAULT 0,
    max_txn_amount      NUMERIC(18,4),                         -- NULL = no upper bound

    -- 2-byte aligned
    fee_type_id         SMALLINT        NOT NULL REFERENCES fee_types(type_id),
    txn_type_id         SMALLINT        NOT NULL REFERENCES txn_types(type_id),

    -- 1-byte aligned
    is_active           BOOL            NOT NULL DEFAULT TRUE,

    -- variable-length
    currency_code       currency_code   NOT NULL REFERENCES currencies(currency_code),
    description         TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 8. EXCHANGE_RATES  — FX rate snapshots (append-only)
-- ---------------------------------------------------------------------------
CREATE TABLE exchange_rates (
    -- 8-byte aligned
    rate_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rate                NUMERIC(18,8)   NOT NULL,
    inverse_rate        NUMERIC(18,8)   NOT NULL,
    spread_bps          INT             NOT NULL DEFAULT 0,
    captured_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- variable-length
    base_currency       currency_code   NOT NULL REFERENCES currencies(currency_code),
    quote_currency      currency_code   NOT NULL REFERENCES currencies(currency_code),
    source              TEXT            NOT NULL DEFAULT 'internal',  -- 'ecb', 'openexchange', etc.
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 9. NOTIFICATIONS
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    -- 8-byte aligned
    notification_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    read_at             TIMESTAMPTZ,

    -- 1-byte aligned
    is_read             BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    channel             TEXT            NOT NULL DEFAULT 'push',  -- 'push', 'sms', 'email', 'in_app'
    title               TEXT            NOT NULL,
    body                TEXT            NOT NULL,
    action_url          TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 10. AUDIT_LOG  — Immutable compliance trail (partitioned quarterly)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    -- 8-byte aligned
    log_id              BIGINT GENERATED ALWAYS AS IDENTITY,
    actor_id            BIGINT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- variable-length
    action              TEXT            NOT NULL,
    entity_type         TEXT            NOT NULL,
    entity_id           TEXT            NOT NULL,
    ip_address          INET,
    user_agent          TEXT,
    old_values          JSONB,
    new_values          JSONB,
    metadata            JSONB           NOT NULL DEFAULT '{}',

    PRIMARY KEY (log_id, created_at)
) PARTITION BY RANGE (created_at);

-- Quarterly partitions are created by maintenance.run_partition_maintenance().


-- =============================================================================
-- =============================================================================
--
--  P A R T I T I O N   M A I N T E N A N C E
--
--  Hard-coded partitions run out: the original list ended in July 2026, after
--  which every insert into transactions would have failed. Instead, each
--  partitioned table is registered here and partitions are created ahead of
--  need. deploy/hetzner.sh schedules maintenance.run_partition_maintenance()
--  daily; it is idempotent and only ever creates partitions.
--
-- =============================================================================
-- =============================================================================

CREATE TABLE maintenance.partition_config (
    parent          REGCLASS    PRIMARY KEY,
    step            INTERVAL    NOT NULL CHECK (step IN ('1 month', '3 months')),
    premake         INTERVAL    NOT NULL DEFAULT '3 months',  -- how far ahead to create
    -- Storage parameters for each new partition, since partitioned parents
    -- cannot carry them. autovacuum_parallel_workers is new in PostgreSQL 19
    -- and only takes effect when the server's autovacuum_max_parallel_workers > 0.
    reloptions      TEXT[]      NOT NULL DEFAULT '{}'
);

-- Creates the missing partitions of p_parent from p_from (default: the
-- current period) up to now() + premake. Partition bounds are UTC.
CREATE OR REPLACE FUNCTION maintenance.create_partitions(
    p_parent REGCLASS,
    p_from   TIMESTAMPTZ DEFAULT NULL
)
RETURNS INT
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
DECLARE
    cfg       maintenance.partition_config%ROWTYPE;
    v_schema  TEXT;
    v_table   TEXT;
    v_part    TEXT;
    v_start   TIMESTAMP;   -- UTC wall-clock
    v_end     TIMESTAMP;
    v_until   TIMESTAMP;
    v_created INT := 0;
BEGIN
    SELECT * INTO STRICT cfg FROM maintenance.partition_config WHERE parent = p_parent;
    SELECT n.nspname, c.relname INTO STRICT v_schema, v_table
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = p_parent;

    v_start := date_trunc(CASE cfg.step WHEN '1 month' THEN 'month' ELSE 'quarter' END,
                          coalesce(p_from, now()) AT TIME ZONE 'UTC');
    v_until := (now() + cfg.premake) AT TIME ZONE 'UTC';

    WHILE v_start < v_until LOOP
        v_end  := v_start + cfg.step;
        v_part := format('%s_%s', v_table, to_char(v_start, 'YYYY_MM'));

        IF to_regclass(format('%I.%I', v_schema, v_part)) IS NULL THEN
            EXECUTE format('CREATE TABLE %I.%I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                           v_schema, v_part, p_parent,
                           v_start AT TIME ZONE 'UTC', v_end AT TIME ZONE 'UTC');
            IF cardinality(cfg.reloptions) > 0 THEN
                EXECUTE format('ALTER TABLE %I.%I SET (%s)',
                               v_schema, v_part, array_to_string(cfg.reloptions, ', '));
            END IF;
            v_created := v_created + 1;
        END IF;
        v_start := v_end;
    END LOOP;

    RETURN v_created;
END;
$$;

CREATE OR REPLACE FUNCTION maintenance.run_partition_maintenance()
RETURNS TABLE (parent REGCLASS, partitions_created INT)
LANGUAGE sql
SET search_path = pg_catalog
AS $$
    SELECT c.parent, maintenance.create_partitions(c.parent)
    FROM maintenance.partition_config c
    ORDER BY c.parent::TEXT;
$$;

-- How long until each table runs out of partitions. Alert well before zero.
CREATE OR REPLACE VIEW maintenance.v_partition_runway AS
SELECT
    c.parent,
    max(b.upper_bound)         AS covered_until,
    max(b.upper_bound) - now() AS runway
FROM maintenance.partition_config c
JOIN pg_inherits i ON i.inhparent = c.parent
JOIN pg_class p ON p.oid = i.inhrelid
CROSS JOIN LATERAL (
    SELECT (regexp_match(pg_get_expr(p.relpartbound, p.oid), 'TO \(''([^'']+)''\)'))[1]::TIMESTAMPTZ
) AS b(upper_bound)
GROUP BY c.parent;

INSERT INTO maintenance.partition_config (parent, step, premake, reloptions) VALUES
    ('transactions',   '1 month',  '3 months', ARRAY[
        'autovacuum_vacuum_scale_factor = 0.005',
        'autovacuum_analyze_scale_factor = 0.002',
        'autovacuum_vacuum_cost_delay = 2',
        'autovacuum_parallel_workers = 2'           -- 8 indexes: vacuum them in parallel
    ]),
    ('ledger_entries', '1 month',  '3 months', ARRAY[
        'autovacuum_vacuum_scale_factor = 0.005',
        'autovacuum_analyze_scale_factor = 0.002',
        -- Append-only: vacuum on inserts often enough to keep the visibility
        -- map current, so statement reads stay index-only.
        'autovacuum_vacuum_insert_scale_factor = 0.02'
    ]),
    ('audit_log',      '3 months', '6 months', ARRAY[
        'autovacuum_vacuum_insert_scale_factor = 0.05'
    ]);

SELECT * FROM maintenance.run_partition_maintenance();

REVOKE ALL ON SCHEMA maintenance FROM PUBLIC;


-- =============================================================================
-- =============================================================================
--
--  A U T H   T A B L E S
--
--  Credentials separated from users for least-privilege column exposure.
--  Sessions, MFA, refresh tokens, login attempts, API keys.
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 11. CREDENTIALS  — Password storage (separate from users table)
-- ---------------------------------------------------------------------------
CREATE TABLE auth.credentials (
    -- 8-byte aligned
    user_id             BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    password_changed_at TIMESTAMPTZ     NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 1-byte aligned
    must_change_password BOOL           NOT NULL DEFAULT FALSE,

    -- variable-length
    password_hash       TEXT            NOT NULL,   -- crypt(password, gen_salt('bf', 12))
    previous_hashes     JSONB           NOT NULL DEFAULT '[]'   -- last N hashes for rotation enforcement
);


-- ---------------------------------------------------------------------------
-- 12. MFA_METHODS  — Multi-factor authentication
-- ---------------------------------------------------------------------------
CREATE TABLE auth.mfa_methods (
    -- 8-byte aligned
    mfa_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 2-byte aligned
    method_type_id      SMALLINT        NOT NULL REFERENCES mfa_method_types(type_id),

    -- 1-byte aligned
    is_primary          BOOL            NOT NULL DEFAULT FALSE,
    is_verified         BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    secret_encrypted    BYTEA,                      -- encrypted TOTP secret (encrypt at app layer)
    phone_number        TEXT,                        -- for SMS-based MFA
    metadata            JSONB           NOT NULL DEFAULT '{}'
);

-- Only one primary MFA method per user
CREATE UNIQUE INDEX idx_mfa_one_primary ON auth.mfa_methods (user_id) WHERE is_primary = TRUE;


-- ---------------------------------------------------------------------------
-- 13. SESSIONS  — Server-side session store for JWT revocation + device tracking
-- ---------------------------------------------------------------------------
CREATE TABLE auth.sessions (
    -- 8-byte aligned
    session_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ     NOT NULL,
    last_active_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,

    -- 2-byte aligned
    revoke_reason_id    SMALLINT        REFERENCES session_revoke_reasons(reason_id),

    -- 1-byte aligned
    is_active           BOOL            NOT NULL DEFAULT TRUE,
    mfa_verified        BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    token_hash          BYTEA           NOT NULL UNIQUE,    -- SHA-256 of the JWT
    device_fingerprint  TEXT,
    ip_address          INET            NOT NULL,
    user_agent          TEXT,
    metadata            JSONB           NOT NULL DEFAULT '{}',

    CONSTRAINT sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_sessions_user_active ON auth.sessions (user_id) WHERE is_active = TRUE;
CREATE INDEX idx_sessions_expires ON auth.sessions (expires_at) WHERE is_active = TRUE;


-- ---------------------------------------------------------------------------
-- 14. REFRESH_TOKENS  — Rotation detection via token families
-- ---------------------------------------------------------------------------
CREATE TABLE auth.refresh_tokens (
    -- 8-byte aligned
    token_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id          BIGINT          NOT NULL REFERENCES auth.sessions(session_id) ON DELETE CASCADE,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ     NOT NULL,
    used_at             TIMESTAMPTZ,                -- set when exchanged for new access token

    -- 1-byte aligned
    is_revoked          BOOL            NOT NULL DEFAULT FALSE,

    -- variable-length
    token_hash          BYTEA           NOT NULL UNIQUE,    -- SHA-256 of refresh token
    token_family        TEXT            NOT NULL             -- for rotation detection (refresh token reuse)
);

CREATE INDEX idx_refresh_tokens_family ON auth.refresh_tokens (token_family);
CREATE INDEX idx_refresh_tokens_user ON auth.refresh_tokens (user_id) WHERE NOT is_revoked;


-- ---------------------------------------------------------------------------
-- 15. LOGIN_ATTEMPTS  — Rate limiting + brute-force detection
-- ---------------------------------------------------------------------------
CREATE TABLE auth.login_attempts (
    -- 8-byte aligned
    attempt_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          REFERENCES users(user_id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 1-byte aligned
    success             BOOL            NOT NULL,

    -- variable-length
    identifier          TEXT            NOT NULL,   -- email or phone used for login
    ip_address          INET            NOT NULL,
    user_agent          TEXT,
    failure_reason      TEXT                        -- 'invalid_password', 'account_locked', 'mfa_failed'
);

CREATE INDEX idx_login_attempts_ip ON auth.login_attempts (ip_address, created_at);
CREATE INDEX idx_login_attempts_identifier ON auth.login_attempts (identifier, created_at);


-- ---------------------------------------------------------------------------
-- 16. API_KEYS  — For merchant/service integrations
-- ---------------------------------------------------------------------------
CREATE TABLE auth.api_keys (
    -- 8-byte aligned
    key_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    last_used_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,

    -- 4-byte aligned
    rate_limit_rpm      INT             NOT NULL DEFAULT 60,    -- requests per minute

    -- 1-byte aligned
    is_active           BOOL            NOT NULL DEFAULT TRUE,

    -- variable-length
    key_prefix          CHAR(8)         NOT NULL,               -- first 8 chars for identification ("pk_live_")
    key_hash            BYTEA           NOT NULL UNIQUE,        -- SHA-256 of the full key
    name                TEXT            NOT NULL,
    scopes              TEXT[]          NOT NULL DEFAULT '{}',   -- ['wallet:read', 'transaction:create']
    metadata            JSONB           NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_api_keys_user ON auth.api_keys (user_id) WHERE is_active = TRUE;
CREATE INDEX idx_api_keys_prefix ON auth.api_keys (key_prefix);


-- =============================================================================
-- =============================================================================
--
--  R B A C   T A B L E S
--
--  Roles, permissions, and user-role assignments.
--  SMALLINT PKs = tiny join cost. Lookup-table pattern.
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 17. ROLES
-- ---------------------------------------------------------------------------
CREATE TABLE core.roles (
    -- 2-byte aligned
    role_id         SMALLINT    PRIMARY KEY,

    -- 1-byte aligned
    is_system       BOOL        NOT NULL DEFAULT FALSE,     -- cannot be deleted

    -- variable-length
    role_name       TEXT        NOT NULL UNIQUE,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO core.roles (role_id, role_name, description, is_system) VALUES
    (1, 'customer',    'Standard fintech customer',   TRUE),
    (2, 'merchant',    'Merchant account',            TRUE),
    (3, 'support',     'Customer support agent',      TRUE),
    (4, 'compliance',  'Compliance / AML officer',    TRUE),
    (5, 'admin',       'System administrator',        TRUE),
    (6, 'super_admin', 'Unrestricted access',         TRUE);


-- ---------------------------------------------------------------------------
-- 18. PERMISSIONS
-- ---------------------------------------------------------------------------
CREATE TABLE core.permissions (
    -- 2-byte aligned
    permission_id   SMALLINT    PRIMARY KEY,

    -- variable-length
    resource        TEXT        NOT NULL,       -- e.g. 'wallet', 'transaction', 'user'
    action          TEXT        NOT NULL,       -- e.g. 'read', 'write', 'transfer', 'admin'
    description     TEXT,

    CONSTRAINT permissions_resource_action_unique UNIQUE (resource, action)
);

INSERT INTO core.permissions (permission_id, resource, action) VALUES
    (1,  'user',        'read'),
    (2,  'user',        'write'),
    (3,  'user',        'delete'),
    (4,  'wallet',      'read'),
    (5,  'wallet',      'write'),
    (6,  'wallet',      'freeze'),
    (7,  'transaction', 'read'),
    (8,  'transaction', 'create'),
    (9,  'transaction', 'reverse'),
    (10, 'kyc',         'read'),
    (11, 'kyc',         'review'),
    (12, 'kyc',         'approve'),
    (13, 'report',      'read'),
    (14, 'report',      'export'),
    (15, 'system',      'admin');


-- ---------------------------------------------------------------------------
-- 19. ROLE_PERMISSIONS  — Many-to-many junction
-- ---------------------------------------------------------------------------
CREATE TABLE core.role_permissions (
    role_id         SMALLINT    NOT NULL REFERENCES core.roles(role_id) ON DELETE CASCADE,
    permission_id   SMALLINT    NOT NULL REFERENCES core.permissions(permission_id) ON DELETE CASCADE,

    PRIMARY KEY (role_id, permission_id)
);


-- ---------------------------------------------------------------------------
-- 20. USER_ROLES  — Supports time-limited elevated access
-- ---------------------------------------------------------------------------
CREATE TABLE core.user_roles (
    -- 8-byte aligned
    user_id         BIGINT      NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    granted_by      BIGINT      REFERENCES users(user_id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,                    -- for time-limited elevated access

    -- 2-byte aligned
    role_id         SMALLINT    NOT NULL REFERENCES core.roles(role_id) ON DELETE CASCADE,

    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_expires ON core.user_roles (expires_at)
    WHERE expires_at IS NOT NULL;


-- =============================================================================
-- =============================================================================
--
--  K Y C   D O C U M E N T S
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 21. KYC_DOCUMENTS  — Uploaded identity documents
-- ---------------------------------------------------------------------------
CREATE TABLE kyc_documents (
    -- 8-byte aligned
    document_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    reviewed_by     BIGINT          REFERENCES users(user_id),
    reviewed_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 2-byte aligned
    status_id       SMALLINT        NOT NULL DEFAULT 2 REFERENCES kyc_statuses(status_id),

    -- variable-length
    document_type   TEXT            NOT NULL,        -- 'passport', 'national_id', 'drivers_license', 'utility_bill', 'selfie'
    file_reference  TEXT            NOT NULL,        -- S3/GCS path (never raw file in DB)
    file_hash       BYTEA           NOT NULL,        -- SHA-256 of uploaded file for integrity
    rejection_reason TEXT,
    metadata        JSONB           NOT NULL DEFAULT '{}',

    CONSTRAINT kyc_doc_reviewed CHECK (
        (status_id IN (4, 5) AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        OR status_id NOT IN (4, 5)
    )
);

CREATE INDEX idx_kyc_docs_user ON kyc_documents (user_id, status_id);
CREATE INDEX idx_kyc_docs_pending ON kyc_documents (created_at) WHERE status_id = 2;


-- =============================================================================
-- =============================================================================
--
--  E N H A N C E D   N O T I F I C A T I O N S
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- 22. NOTIFICATION_TEMPLATES  — Reusable templates per channel
-- ---------------------------------------------------------------------------
CREATE TABLE notify.templates (
    -- 8-byte aligned
    template_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- 2-byte aligned
    channel_id          SMALLINT        NOT NULL REFERENCES notification_channels(channel_id),

    -- 1-byte aligned
    is_active           BOOL            NOT NULL DEFAULT TRUE,

    -- variable-length
    code                TEXT            NOT NULL UNIQUE,     -- 'txn_completed', 'login_new_device'
    subject_template    TEXT,
    body_template       TEXT            NOT NULL,
    metadata            JSONB           NOT NULL DEFAULT '{}'
);


-- ---------------------------------------------------------------------------
-- 23. NOTIFICATION_PREFERENCES  — Per-user, per-channel, per-event opt-in/out
-- ---------------------------------------------------------------------------
CREATE TABLE notify.preferences (
    -- 8-byte aligned
    user_id         BIGINT      NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,

    -- 2-byte aligned
    channel_id      SMALLINT    NOT NULL REFERENCES notification_channels(channel_id),

    -- 1-byte aligned
    is_enabled      BOOL        NOT NULL DEFAULT TRUE,

    -- variable-length
    event_type      TEXT        NOT NULL,    -- 'transaction_complete', 'login_new_device', etc.

    PRIMARY KEY (user_id, channel_id, event_type)
);


-- =============================================================================
-- =============================================================================
--
--  A U D I T   I M M U T A B I L I T Y   +   T R I G G E R S
--
-- =============================================================================
-- =============================================================================


-- Prevent modifications to audit records
CREATE OR REPLACE FUNCTION audit.prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are immutable. UPDATE and DELETE are not allowed.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit.prevent_audit_modification();


-- Automatic updated_at trigger
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_credentials_updated_at
    BEFORE UPDATE ON auth.credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- =============================================================================
-- =============================================================================
--
--  R O L E S   &   R O W - L E V E L   S E C U R I T Y
--
-- =============================================================================
-- =============================================================================


-- Application roles (idempotent creation)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        CREATE ROLE wallet_app LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_readonly') THEN
        CREATE ROLE wallet_readonly LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_admin') THEN
        CREATE ROLE wallet_admin LOGIN;
    END IF;
END $$;

-- Grant schema access
GRANT USAGE ON SCHEMA auth, core, notify, audit TO wallet_app;
GRANT USAGE ON SCHEMA core, audit TO wallet_readonly;
GRANT USAGE ON SCHEMA auth, core, notify, audit TO wallet_admin;

-- wallet_app: read/write on operational tables
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA auth TO wallet_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA core TO wallet_app;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA notify TO wallet_app;
GRANT SELECT, INSERT ON audit_log TO wallet_app;
-- wallet_app has RLS policies on these tables, which do nothing without privileges.
GRANT SELECT ON
    kyc_statuses, wallet_types, wallet_statuses, txn_types, txn_statuses,
    payment_method_types, payment_method_statuses, ledger_entry_types, currencies,
    fee_types, mfa_method_types, notification_channels, delivery_statuses,
    session_revoke_reasons, fee_schedule, exchange_rates, merchants
    TO wallet_app, wallet_readonly;
GRANT SELECT, INSERT, UPDATE ON
    users, wallets, payment_methods, transactions, transaction_references,
    notifications, kyc_documents
    TO wallet_app;
GRANT SELECT, INSERT ON ledger_entries TO wallet_app;   -- the ledger is append-only

-- wallet_readonly: read-only for dashboards/analytics
GRANT SELECT ON ALL TABLES IN SCHEMA core TO wallet_readonly;
GRANT SELECT ON audit_log TO wallet_readonly;

-- Sequences
GRANT USAGE ON ALL SEQUENCES IN SCHEMA auth TO wallet_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA core TO wallet_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA notify TO wallet_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO wallet_app;

-- ---------------------------------------------------------------------------
-- RLS POLICIES
-- ---------------------------------------------------------------------------

-- Users can only see their own record
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY users_own_data ON users
    FOR ALL TO wallet_app
    USING (user_id = current_setting('app.current_user_id', TRUE)::BIGINT);
CREATE POLICY users_admin_full ON users
    FOR ALL TO wallet_admin
    USING (TRUE);

-- Users can only see their own wallets
ALTER TABLE wallets ENABLE ROW LEVEL SECURITY;
CREATE POLICY wallets_own_data ON wallets
    FOR ALL TO wallet_app
    USING (user_id = current_setting('app.current_user_id', TRUE)::BIGINT);

-- Users can only see their own transactions (via wallet ownership)
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
CREATE POLICY transactions_own_data ON transactions
    FOR ALL TO wallet_app
    USING (
        source_wallet_id IN (SELECT w.wallet_id FROM wallets w WHERE w.user_id = current_setting('app.current_user_id', TRUE)::BIGINT)
        OR
        dest_wallet_id IN (SELECT w.wallet_id FROM wallets w WHERE w.user_id = current_setting('app.current_user_id', TRUE)::BIGINT)
    );

-- Users can only see their own ledger entries
ALTER TABLE ledger_entries ENABLE ROW LEVEL SECURITY;
CREATE POLICY ledger_own_data ON ledger_entries
    FOR ALL TO wallet_app
    USING (
        wallet_id IN (SELECT w.wallet_id FROM wallets w WHERE w.user_id = current_setting('app.current_user_id', TRUE)::BIGINT)
    );

-- Users can only see their own sessions
ALTER TABLE auth.sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY sessions_own_data ON auth.sessions
    FOR ALL TO wallet_app
    USING (user_id = current_setting('app.current_user_id', TRUE)::BIGINT);

-- Users can only see their own notifications
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_own_data ON notifications
    FOR ALL TO wallet_app
    USING (user_id = current_setting('app.current_user_id', TRUE)::BIGINT);

-- Audit: app can INSERT, admins can SELECT
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_insert_only ON audit_log
    FOR INSERT TO wallet_app
    WITH CHECK (TRUE);
CREATE POLICY audit_admin_read ON audit_log
    FOR SELECT TO wallet_admin
    USING (TRUE);


-- =============================================================================
-- =============================================================================
--
--  S E C U R I T Y   H A R D E N I N G
--
-- =============================================================================
-- =============================================================================


-- Revoke public access
REVOKE ALL ON ALL TABLES IN SCHEMA auth FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA core FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA notify FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA audit FROM PUBLIC;

-- Prevent wallet_app from modifying audit records
REVOKE UPDATE, DELETE ON audit_log FROM wallet_app;

-- Prevent anyone from reading credential hashes directly
REVOKE SELECT ON auth.credentials FROM wallet_readonly;

-- Set default privileges for future tables
ALTER DEFAULT PRIVILEGES IN SCHEMA auth   GRANT SELECT, INSERT, UPDATE ON TABLES TO wallet_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA core   GRANT SELECT, INSERT, UPDATE ON TABLES TO wallet_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA notify GRANT SELECT, INSERT, UPDATE ON TABLES TO wallet_app;

-- Statement timeout to prevent runaway queries
ALTER ROLE wallet_app SET statement_timeout = '30s';
ALTER ROLE wallet_readonly SET statement_timeout = '60s';

-- Lock timeout to prevent indefinite lock waits
ALTER ROLE wallet_app SET lock_timeout = '10s';

-- Idle-in-transaction timeout
ALTER ROLE wallet_app SET idle_in_transaction_session_timeout = '60s';


-- =============================================================================
-- =============================================================================
--
--  A U T H   F U N C T I O N S
--
-- =============================================================================
-- =============================================================================


-- ---------------------------------------------------------------------------
-- Password verification with constant-time defence + brute-force lockout.
-- SECURITY DEFINER with a fixed search_path: without it a caller could put
-- their own crypt() or users table ahead of ours.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth.verify_password(
    p_email    CITEXT,
    p_password TEXT
)
RETURNS TABLE(out_user_id BIGINT, is_valid BOOLEAN, is_locked BOOLEAN) AS $$
DECLARE
    v_user       users%ROWTYPE;
    v_hash       TEXT;
    v_now        TIMESTAMPTZ := now();
BEGIN
    SELECT u.* INTO v_user FROM users u WHERE u.email = p_email;

    IF NOT FOUND THEN
        -- Constant-time: still do a hash comparison to prevent timing attacks
        PERFORM crypt(p_password, gen_salt('bf', 12));
        RETURN QUERY SELECT NULL::BIGINT, FALSE, FALSE;
        RETURN;
    END IF;

    -- Check if account is locked (locked_until not in users, use is_blocked)
    IF v_user.is_blocked THEN
        RETURN QUERY SELECT v_user.user_id, FALSE, TRUE;
        RETURN;
    END IF;

    -- Verify password
    SELECT c.password_hash INTO v_hash
    FROM auth.credentials c WHERE c.user_id = v_user.user_id;

    IF v_hash IS NOT NULL AND crypt(p_password, v_hash) = v_hash THEN
        -- Success: reset failed count
        UPDATE users SET failed_login_count = 0
        WHERE users.user_id = v_user.user_id;

        RETURN QUERY SELECT v_user.user_id, TRUE, FALSE;
    ELSE
        -- Failure: increment counter, block after 5 attempts
        UPDATE users
        SET failed_login_count = failed_login_count + 1,
            is_blocked = CASE WHEN failed_login_count + 1 >= 5 THEN TRUE ELSE is_blocked END
        WHERE users.user_id = v_user.user_id;

        RETURN QUERY SELECT v_user.user_id, FALSE, (v_user.failed_login_count + 1 >= 5);
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;


-- ---------------------------------------------------------------------------
-- Session management
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION auth.create_session(
    p_user_id           BIGINT,
    p_token_hash        BYTEA,
    p_ip_address        INET,
    p_user_agent        TEXT DEFAULT NULL,
    p_device_fingerprint TEXT DEFAULT NULL,
    p_ttl_hours         INT DEFAULT 24
)
RETURNS BIGINT AS $$
DECLARE
    v_session_id BIGINT;
    v_max_sessions INT := 5;
    v_active_count INT;
BEGIN
    SELECT COUNT(*) INTO v_active_count
    FROM auth.sessions WHERE user_id = p_user_id AND is_active = TRUE;

    -- Revoke oldest if at limit
    IF v_active_count >= v_max_sessions THEN
        UPDATE auth.sessions
        SET is_active = FALSE,
            revoked_at = now(),
            revoke_reason_id = 5    -- max_sessions
        WHERE session_id = (
            SELECT session_id FROM auth.sessions
            WHERE user_id = p_user_id AND is_active = TRUE
            ORDER BY last_active_at ASC
            LIMIT 1
        );
    END IF;

    INSERT INTO auth.sessions (
        user_id, token_hash, ip_address, user_agent,
        device_fingerprint, expires_at
    ) VALUES (
        p_user_id, p_token_hash, p_ip_address, p_user_agent,
        p_device_fingerprint, now() + (p_ttl_hours || ' hours')::INTERVAL
    )
    RETURNING session_id INTO v_session_id;

    RETURN v_session_id;
END;
$$ LANGUAGE plpgsql;


-- Revoke all sessions for a user (e.g., on password change)
CREATE OR REPLACE FUNCTION auth.revoke_all_sessions(
    p_user_id       BIGINT,
    p_reason_id     SMALLINT DEFAULT 2,         -- password_change
    p_except_session BIGINT DEFAULT NULL
)
RETURNS INT AS $$
DECLARE
    v_count INT;
BEGIN
    UPDATE auth.sessions
    SET is_active = FALSE,
        revoked_at = now(),
        revoke_reason_id = p_reason_id
    WHERE user_id = p_user_id
      AND is_active = TRUE
      AND (p_except_session IS NULL OR session_id != p_except_session);

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;


-- ---------------------------------------------------------------------------
-- Cleanup expired data (run via pg_cron or external scheduler)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE auth.cleanup_expired()
LANGUAGE plpgsql AS $$
BEGIN
    -- Expire sessions
    UPDATE auth.sessions
    SET is_active = FALSE, revoked_at = now(), revoke_reason_id = 4     -- expired
    WHERE is_active = TRUE AND expires_at < now();

    -- Expire refresh tokens
    UPDATE auth.refresh_tokens
    SET is_revoked = TRUE
    WHERE NOT is_revoked AND expires_at < now();

    -- Expire pending transactions
    UPDATE transactions
    SET status_id = 6       -- expired
    WHERE status_id = 1 AND expires_at IS NOT NULL AND expires_at < now();
END;
$$;


-- =============================================================================
-- =============================================================================
--
--  M O N I T O R I N G   V I E W S
--
-- =============================================================================
-- =============================================================================


-- Session health
CREATE OR REPLACE VIEW auth.v_session_stats AS
SELECT
    COUNT(*) FILTER (WHERE is_active AND expires_at > now())     AS active_sessions,
    COUNT(*) FILTER (WHERE is_active AND expires_at <= now())    AS expired_not_cleaned,
    COUNT(*) FILTER (WHERE NOT is_active)                        AS revoked_sessions,
    COUNT(DISTINCT user_id) FILTER (WHERE is_active)             AS unique_active_users,
    MAX(created_at) FILTER (WHERE is_active)                     AS latest_session
FROM auth.sessions;

-- Failed login rate (last hour)
CREATE OR REPLACE VIEW auth.v_login_health AS
SELECT
    COUNT(*)                                                    AS total_attempts,
    COUNT(*) FILTER (WHERE success)                             AS successful,
    COUNT(*) FILTER (WHERE NOT success)                         AS failed,
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE NOT success) / GREATEST(COUNT(*), 1),
        2
    ) AS failure_rate_pct,
    COUNT(DISTINCT ip_address) FILTER (WHERE NOT success)       AS distinct_failed_ips
FROM auth.login_attempts
WHERE created_at > now() - INTERVAL '1 hour';

-- Blocked queries
-- pg_blocking_pids() is exact and much cheaper than self-joining pg_locks.
CREATE OR REPLACE VIEW v_blocked_queries AS
SELECT
    blocked.pid       AS blocked_pid,
    blocked.query     AS blocked_query,
    age(now(), blocked.query_start) AS waiting_time,
    blocker.pid       AS blocker_pid,
    blocker.query     AS blocker_query,
    blocker.state     AS blocker_state
FROM pg_stat_activity blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(pid)
JOIN pg_stat_activity blocker ON blocker.pid = b.pid;

-- Long-running transactions
CREATE OR REPLACE VIEW v_long_transactions AS
SELECT pid, usename, state, query,
       age(now(), xact_start) AS txn_age,
       age(now(), query_start) AS query_age,
       wait_event_type, wait_event
FROM pg_stat_activity
WHERE state != 'idle'
  AND xact_start < now() - INTERVAL '30 seconds'
ORDER BY xact_start;

-- Transaction ID wraparound monitoring
CREATE OR REPLACE VIEW v_wraparound_risk AS
SELECT datname,
       age(datfrozenxid) AS xid_age,
       current_setting('autovacuum_freeze_max_age')::BIGINT AS freeze_threshold,
       ROUND(100.0 * age(datfrozenxid) /
             current_setting('autovacuum_freeze_max_age')::BIGINT, 1) AS pct_to_wraparound
FROM pg_database
WHERE datallowconn
ORDER BY xid_age DESC;


-- =============================================================================
-- =============================================================================
--
--  C O M M E N T S  — Schema documentation
--
-- =============================================================================
-- =============================================================================

COMMENT ON SCHEMA auth   IS 'Authentication: credentials, sessions, MFA, API keys, login tracking';
COMMENT ON SCHEMA core   IS 'RBAC: roles, permissions, user-role assignments';
COMMENT ON SCHEMA notify IS 'Notification templates, preferences, delivery tracking';
COMMENT ON SCHEMA audit  IS 'Immutable audit trail — UPDATE/DELETE blocked by trigger';

COMMENT ON TABLE auth.credentials IS 'Password hashes stored separately from users. Only accessible via SECURITY DEFINER functions.';
COMMENT ON TABLE auth.sessions IS 'Server-side session store for JWT revocation and device tracking.';
COMMENT ON TABLE audit_log IS 'Append-only audit log. UPDATE/DELETE blocked by trigger.';
COMMENT ON TABLE ledger_entries IS 'Double-entry ledger. Every debit has a matching credit. Sum must always balance.';
COMMENT ON TABLE wallets IS 'User wallets. balance is denormalized — ledger_entries is the source of truth.';
COMMENT ON TABLE transactions IS 'Partitioned by month; see maintenance.partition_config. Idempotency is enforced by transaction_references.';
COMMENT ON TABLE transaction_references IS 'One row per client idempotency key; claimed with INSERT ... ON CONFLICT DO SELECT FOR UPDATE.';

COMMENT ON FUNCTION auth.verify_password IS 'Constant-time password verification with brute-force lockout. SECURITY DEFINER.';
COMMENT ON FUNCTION auth.create_session IS 'Creates a session with auto-eviction of oldest when at max concurrent limit.';
COMMENT ON FUNCTION auth.revoke_all_sessions IS 'Revokes all active sessions for a user, used on password change.';


-- =============================================================================
-- INDEXES
-- =============================================================================

-- USERS -------------------------------------------------------------------
-- Indexes on virtual generated columns are not supported; index the expression
-- full_name expands to instead. Queries on full_name use it.
CREATE INDEX idx_user_name_trgm ON users USING GIN ((first_name || ' ' || last_name) gin_trgm_ops);
CREATE INDEX idx_user_kyc_pending ON users (created_at DESC) WHERE kyc_status_id = 2;
CREATE INDEX idx_user_high_risk ON users (risk_score DESC) WHERE risk_score > 700;

-- WALLETS -----------------------------------------------------------------
CREATE INDEX idx_wallet_user ON wallets (user_id) INCLUDE (currency_code, balance, status_id);
CREATE INDEX idx_wallet_active ON wallets (user_id, currency_code) WHERE status_id = 2;

-- PAYMENT_METHODS ---------------------------------------------------------
CREATE INDEX idx_pm_user ON payment_methods (user_id, type_id) WHERE status_id = 2;

-- TRANSACTIONS ------------------------------------------------------------
CREATE INDEX idx_txn_source ON transactions (source_wallet_id, created_at DESC);
CREATE INDEX idx_txn_dest   ON transactions (dest_wallet_id, created_at DESC);
CREATE INDEX idx_txn_pending ON transactions (created_at) WHERE status_id = 1;
CREATE INDEX idx_txn_suspicious ON transactions (created_at DESC) WHERE is_suspicious = TRUE;
CREATE INDEX idx_txn_merchant ON transactions (merchant_id, created_at DESC) WHERE merchant_id IS NOT NULL;
CREATE INDEX idx_txn_brin ON transactions USING BRIN (created_at) WITH (pages_per_range = 32);

-- LEDGER_ENTRIES ----------------------------------------------------------
CREATE INDEX idx_ledger_wallet ON ledger_entries (wallet_id, created_at DESC);
CREATE INDEX idx_ledger_txn ON ledger_entries (txn_id, txn_created_at);
CREATE INDEX idx_ledger_brin ON ledger_entries USING BRIN (created_at) WITH (pages_per_range = 32);

-- EXCHANGE_RATES ----------------------------------------------------------
CREATE INDEX idx_fx_pair ON exchange_rates (base_currency, quote_currency, captured_at DESC);

-- NOTIFICATIONS -----------------------------------------------------------
CREATE INDEX idx_notif_user_unread ON notifications (user_id, created_at DESC) WHERE is_read = FALSE;

-- AUDIT_LOG ---------------------------------------------------------------
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_brin ON audit_log USING BRIN (created_at);

-- AUTH.SESSIONS -----------------------------------------------------------
CREATE INDEX idx_session_device ON auth.sessions (user_id, device_fingerprint) WHERE is_active = TRUE;

-- AUTH.API_KEYS -----------------------------------------------------------
CREATE INDEX idx_api_keys_expires ON auth.api_keys (expires_at) WHERE is_active = TRUE AND expires_at IS NOT NULL;

-- AUTH.LOGIN_ATTEMPTS -----------------------------------------------------
CREATE INDEX idx_login_recent ON auth.login_attempts (created_at DESC) WHERE NOT success;

-- NOTIFICATIONS -----------------------------------------------------------
CREATE INDEX idx_notif_delivery ON notifications (created_at) WHERE is_read = FALSE;
