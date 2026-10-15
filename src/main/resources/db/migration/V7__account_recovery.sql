ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE auth_session (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO auth_session(id,user_id,expires_at,revoked_at,created_at)
SELECT id,user_id,expires_at,revoked_at,created_at FROM refresh_token;
ALTER TABLE refresh_token ADD COLUMN session_id BIGINT REFERENCES auth_session(id) ON DELETE CASCADE;
UPDATE refresh_token SET session_id=id;
ALTER TABLE refresh_token ALTER COLUMN session_id SET NOT NULL;
CREATE INDEX idx_auth_session_user ON auth_session(user_id);
CREATE INDEX idx_refresh_token_session ON refresh_token(session_id);

CREATE TABLE auth_action_token (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK(purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_action_user_purpose ON auth_action_token(user_id,purpose,created_at);

CREATE TABLE auth_mail_outbox (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK(purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    payload_ciphertext TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_mail_dispatch ON auth_mail_outbox(status,next_attempt_at);
