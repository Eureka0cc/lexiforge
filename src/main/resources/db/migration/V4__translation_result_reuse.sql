ALTER TABLE translation ADD COLUMN request_fingerprint VARCHAR(64);
CREATE INDEX idx_translation_reuse
    ON translation(user_id, request_fingerprint, created_at DESC, id DESC);
