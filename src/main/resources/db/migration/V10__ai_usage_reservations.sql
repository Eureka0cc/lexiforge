CREATE TABLE ai_usage_reservation (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    usage_type VARCHAR(30) NOT NULL,
    operation_key VARCHAR(200) NOT NULL,
    usage_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('RESERVED','COMMITTED','RELEASED')),
    result_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_ai_usage_active ON ai_usage_reservation(user_id,operation_key) WHERE status='RESERVED';
CREATE INDEX idx_ai_usage_expiry ON ai_usage_reservation(expires_at) WHERE status='RESERVED';
