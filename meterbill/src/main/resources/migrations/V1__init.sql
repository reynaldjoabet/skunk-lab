-- ─── Enums ───
CREATE TYPE plan_tier       AS ENUM ('free', 'starter', 'pro', 'enterprise');
CREATE TYPE billing_period  AS ENUM ('monthly', 'yearly');
CREATE TYPE invoice_status  AS ENUM ('draft', 'open', 'paid', 'void', 'uncollectible');
CREATE TYPE sub_status      AS ENUM ('active', 'past_due', 'canceled', 'trialing');

-- ─── Tenants ───
CREATE TABLE tenants (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug        VARCHAR(63)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]*[a-z0-9]$'),
  name        VARCHAR(255) NOT NULL,
  email       VARCHAR(255) NOT NULL UNIQUE,
  metadata    JSONB        NOT NULL DEFAULT '{}',
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ─── Plans ───
CREATE TABLE plans (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name               VARCHAR(100) NOT NULL,
  tier               plan_tier      NOT NULL,
  period             billing_period NOT NULL,
  base_price_cents   BIGINT NOT NULL CHECK (base_price_cents >= 0),
  included_units     BIGINT NOT NULL DEFAULT 0,
  overage_rate_cents BIGINT NOT NULL DEFAULT 0,
  active             BOOLEAN NOT NULL DEFAULT true,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (name, period)
);

-- Seed plans
INSERT INTO plans (name, tier, period, base_price_cents, included_units, overage_rate_cents) VALUES
  ('Free',       'free',       'monthly',     0,  1000, 0),
  ('Starter',    'starter',    'monthly',  2900, 50000, 5),
  ('Pro',        'pro',        'monthly',  9900, 500000, 2),
  ('Enterprise', 'enterprise', 'monthly', 49900, 5000000, 1),
  ('Starter',    'starter',    'yearly',  29000, 50000, 5),
  ('Pro',        'pro',        'yearly',  99000, 500000, 2);

-- ─── Subscriptions ───
CREATE TABLE subscriptions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenants(id),
  plan_id     UUID NOT NULL REFERENCES plans(id),
  status      sub_status NOT NULL DEFAULT 'active',
  starts_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  current_period_start TIMESTAMPTZ NOT NULL DEFAULT date_trunc('month', now()),
  current_period_end   TIMESTAMPTZ NOT NULL DEFAULT date_trunc('month', now()) + interval '1 month',
  canceled_at TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, plan_id)
);

-- ─── Usage Events (append-only) ───
CREATE TABLE usage_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID        NOT NULL REFERENCES tenants(id),
  subscription_id UUID        NOT NULL REFERENCES subscriptions(id),
  metric          VARCHAR(50) NOT NULL,
  quantity        BIGINT      NOT NULL CHECK (quantity > 0),
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_tenant_metric_time
  ON usage_events (tenant_id, metric, recorded_at DESC);
CREATE INDEX idx_usage_sub_period
  ON usage_events (subscription_id, recorded_at);

-- ─── Invoices ───
CREATE TABLE invoices (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenants(id),
  subscription_id UUID NOT NULL REFERENCES subscriptions(id),
  period_start    TIMESTAMPTZ NOT NULL,
  period_end      TIMESTAMPTZ NOT NULL,
  base_amount     BIGINT NOT NULL,
  overage_units   BIGINT NOT NULL DEFAULT 0,
  overage_amount  BIGINT NOT NULL DEFAULT 0,
  total_amount    BIGINT NOT NULL,
  status          invoice_status NOT NULL DEFAULT 'draft',
  issued_at       TIMESTAMPTZ,
  paid_at         TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Prevent duplicate invoices for same period
  UNIQUE (subscription_id, period_start, period_end)
);

CREATE TABLE line_items (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_id       UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  description      TEXT   NOT NULL,
  quantity         BIGINT NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  amount_cents     BIGINT NOT NULL
);

CREATE INDEX idx_invoices_tenant ON invoices (tenant_id, created_at DESC);
CREATE INDEX idx_invoices_status ON invoices (status) WHERE status IN ('draft', 'open');