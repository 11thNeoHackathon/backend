CREATE TABLE reflection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    completed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (chapter_id, participant_id),
    CONSTRAINT fk_reflection_story FOREIGN KEY (chapter_id, participant_id)
        REFERENCES story(chapter_id, author_id) ON DELETE CASCADE,
    CONSTRAINT chk_reflection_content CHECK (TRIM(content) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
