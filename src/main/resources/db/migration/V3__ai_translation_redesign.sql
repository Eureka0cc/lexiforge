ALTER TABLE translation
    ADD COLUMN context TEXT,
    ADD COLUMN alternatives_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN model_version VARCHAR(100),
    ADD COLUMN prompt_version VARCHAR(40);

CREATE TABLE ai_request (
    id BIGINT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    operation VARCHAR(30) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    usage_date DATE NOT NULL,
    model_version VARCHAR(100),
    prompt_version VARCHAR(40),
    duration_ms BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    error_code VARCHAR(80),
    result_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_request_user_request UNIQUE(user_id, request_id),
    CONSTRAINT ck_ai_request_status CHECK(status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED'))
);

CREATE INDEX idx_ai_request_pending ON ai_request(status, created_at);

CREATE TABLE translation_lookup (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    translation_id BIGINT NOT NULL REFERENCES translation(id) ON DELETE CASCADE,
    side VARCHAR(10) NOT NULL,
    selection_start INTEGER NOT NULL,
    selection_end INTEGER NOT NULL,
    selected_text VARCHAR(120) NOT NULL,
    meaning VARCHAR(500) NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    counterpart_text VARCHAR(120),
    learning_word VARCHAR(120),
    chinese_meaning VARCHAR(500),
    ipa VARCHAR(100),
    part_of_speech VARCHAR(40),
    example_sentence VARCHAR(1000),
    example_translation VARCHAR(1000),
    details_loaded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_translation_lookup_side CHECK(side IN ('SOURCE','TARGET')),
    CONSTRAINT uq_translation_lookup_selection UNIQUE(translation_id, side, selection_start, selection_end)
);

CREATE INDEX idx_translation_lookup_user_translation ON translation_lookup(user_id, translation_id);

ALTER TABLE daily_usage ADD COLUMN lookup_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE vocabulary_item ADD COLUMN sense_key VARCHAR(700);
WITH normalized AS (
    SELECT id,
           lower(regexp_replace(normalize(trim(word), NFC), '\s+', ' ', 'g')) || '|' ||
           lower(regexp_replace(normalize(trim(chinese_meaning), NFC), '\s+', ' ', 'g')) AS base_key,
           row_number() OVER (
               PARTITION BY user_id,
                   lower(regexp_replace(normalize(trim(word), NFC), '\s+', ' ', 'g')),
                   lower(regexp_replace(normalize(trim(chinese_meaning), NFC), '\s+', ' ', 'g'))
               ORDER BY created_at, id
           ) AS duplicate_number
    FROM vocabulary_item
)
UPDATE vocabulary_item item
SET sense_key = normalized.base_key || CASE WHEN normalized.duplicate_number = 1
    THEN '' ELSE '|legacy:' || item.id::text END
FROM normalized
WHERE normalized.id = item.id;
ALTER TABLE vocabulary_item ALTER COLUMN sense_key SET NOT NULL;
ALTER TABLE vocabulary_item DROP CONSTRAINT uq_user_word;
ALTER TABLE vocabulary_item ADD CONSTRAINT uq_user_vocabulary_sense UNIQUE(user_id, sense_key);
