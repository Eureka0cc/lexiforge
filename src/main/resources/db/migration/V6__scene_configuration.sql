ALTER TABLE translation_domain ADD COLUMN configuration_json TEXT;
ALTER TABLE translation_domain ADD COLUMN configuration_version VARCHAR(40);
ALTER TABLE translation_domain ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE translation ADD COLUMN scene_revision BIGINT;
ALTER TABLE translation ADD COLUMN scene_snapshot_json TEXT;
ALTER TABLE daily_usage ADD COLUMN scene_generation_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE scene_draft (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(500) NOT NULL,
    configuration_json TEXT NOT NULL,
    generation_version VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_scene_draft_user ON scene_draft(user_id, created_at);
