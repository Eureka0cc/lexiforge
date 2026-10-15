CREATE TABLE billing_order (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_id VARCHAR(64) NOT NULL,
    channel VARCHAR(30) NOT NULL CHECK(channel IN ('ALIPAY_SIMULATOR','WECHAT_SIMULATOR')),
    cycle VARCHAR(10) NOT NULL CHECK(cycle IN ('MONTH','ANNUAL')),
    plan_code VARCHAR(30) NOT NULL,
    plan_name VARCHAR(60) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    duration_days INTEGER NOT NULL CHECK(duration_days IN (30,365)),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PAID','REFUNDED','CANCELLED')),
    trade_no VARCHAR(80) UNIQUE,
    last_event_version INTEGER NOT NULL DEFAULT 0,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now()+interval '30 minutes',
    UNIQUE(user_id,request_id)
);
CREATE INDEX idx_billing_order_user ON billing_order(user_id,created_at);
CREATE TABLE billing_sandbox_trade (
    order_id BIGINT PRIMARY KEY REFERENCES billing_order(id) ON DELETE CASCADE,
    trade_no VARCHAR(80) NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    channel VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('PAID','REFUNDED')),
    version INTEGER NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE billing_event (
    order_id BIGINT NOT NULL REFERENCES billing_order(id) ON DELETE CASCADE,
    event_version INTEGER NOT NULL,
    event_status VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(order_id,event_version)
);
CREATE TABLE billing_account (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    base_starts_at TIMESTAMPTZ,
    base_expires_at TIMESTAMPTZ
);
CREATE TABLE billing_grant (
    order_id BIGINT PRIMARY KEY REFERENCES billing_order(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_billing_grant_user ON billing_grant(user_id);
