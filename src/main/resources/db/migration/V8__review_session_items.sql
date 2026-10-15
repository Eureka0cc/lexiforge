-- Old active sessions did not record their selected items and cannot be validated safely.
UPDATE review_session SET status='FINISHED',finished_at=now() WHERE status='ACTIVE';

CREATE TABLE review_session_item (
    session_id BIGINT NOT NULL REFERENCES review_session(id) ON DELETE CASCADE,
    vocabulary_id BIGINT NOT NULL REFERENCES vocabulary_item(id) ON DELETE CASCADE,
    direction VARCHAR(20) NOT NULL CHECK(direction IN ('EN_TO_ZH','ZH_TO_EN')),
    word_text TEXT NOT NULL,
    expected_answer TEXT NOT NULL,
    part_of_speech VARCHAR(120),
    context TEXT,
    answer_text TEXT,
    response_json TEXT,
    answered_at TIMESTAMPTZ,
    PRIMARY KEY(session_id,vocabulary_id,direction)
);
CREATE INDEX idx_review_session_item_vocabulary ON review_session_item(vocabulary_id);
