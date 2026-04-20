-- Custom enum types
CREATE TYPE account_status  AS ENUM ('active', 'frozen', 'closed');
CREATE TYPE kyc_level       AS ENUM ('none', 'basic', 'verified', 'enhanced');
CREATE TYPE tx_status       AS ENUM ('pending', 'completed', 'failed', 'reversed');
CREATE TYPE tx_type         AS ENUM ('deposit', 'withdrawal', 'transfer', 'fee', 'refund');
CREATE TYPE currency_code   AS ENUM ('USD', 'EUR', 'GBP', 'CAD', 'NGN');

-- Users
CREATE TABLE users (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email         VARCHAR(255) NOT NULL UNIQUE,
  full_name     VARCHAR(255) NOT NULL,
  kyc_level     kyc_level    NOT NULL DEFAULT 'none',
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- Accounts (one user can have multiple currency accounts)
CREATE TABLE accounts (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID           NOT NULL REFERENCES users(id),
  currency      currency_code  NOT NULL,
  balance       NUMERIC(19,4)  NOT NULL DEFAULT 0 CHECK (balance >= 0),
  status        account_status NOT NULL DEFAULT 'active',
  created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
  UNIQUE (user_id, currency)
);

CREATE INDEX idx_accounts_user ON accounts (user_id);

-- Transactions (immutable ledger)
CREATE TABLE transactions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  tx_type         tx_type       NOT NULL,
  status          tx_status     NOT NULL DEFAULT 'pending',
  source_acct_id  UUID          REFERENCES accounts(id),
  dest_acct_id    UUID          REFERENCES accounts(id),
  amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency        currency_code NOT NULL,
  description     TEXT,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ,
  CHECK (source_acct_id IS NOT NULL OR dest_acct_id IS NOT NULL)
);

CREATE INDEX idx_tx_source    ON transactions (source_acct_id, created_at DESC);
CREATE INDEX idx_tx_dest      ON transactions (dest_acct_id, created_at DESC);
CREATE INDEX idx_tx_idempotency ON transactions (idempotency_key);

-- Audit log (append-only)
CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  entity_type VARCHAR(50)  NOT NULL,
  entity_id   UUID         NOT NULL,
  action      VARCHAR(50)  NOT NULL,
  actor_id    UUID,
  payload     JSONB,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, created_at DESC);