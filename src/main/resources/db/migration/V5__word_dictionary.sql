CREATE TABLE word_dictionary (
    id BIGINT PRIMARY KEY,
    word VARCHAR(120) NOT NULL,
    source_language VARCHAR(10) NOT NULL,
    target_language VARCHAR(10) NOT NULL,
    domain_id BIGINT NOT NULL REFERENCES translation_domain(id) ON DELETE CASCADE,
    translated_text TEXT NOT NULL,
    detected_language VARCHAR(10) NOT NULL,
    alternatives_json TEXT NOT NULL DEFAULT '[]',
    model_version VARCHAR(100),
    prompt_version VARCHAR(40),
    hit_count BIGINT NOT NULL DEFAULT 0,
    contributor_user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_word_dictionary_entry UNIQUE(word, source_language, target_language, domain_id)
);

-- Must stay byte-identical to the WHERE expression in
-- WordDictionaryRepository.countDistinctRequesters so the count can use this index.
CREATE INDEX idx_translation_word_admission ON translation(
    domain_id,
    target_language,
    lower(regexp_replace(normalize(trim(source_text), NFC), '\s+', ' ', 'g')),
    user_id
);
