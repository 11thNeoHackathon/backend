CREATE TABLE chapter (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    opened_at DATETIME(6),
    INDEX idx_chapter_book (book_id, id),
    CONSTRAINT fk_chapter_book FOREIGN KEY (book_id) REFERENCES book(id),
    CONSTRAINT fk_chapter_creator FOREIGN KEY (book_id, created_by)
        REFERENCES book_participant(book_id, participant_id),
    CONSTRAINT chk_chapter_name CHECK (TRIM(name) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE story (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    mission_instruction TEXT NOT NULL,
    completed_at DATETIME(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (chapter_id, author_id),
    CONSTRAINT fk_story_chapter FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE,
    CONSTRAINT fk_story_author FOREIGN KEY (author_id) REFERENCES participant(id),
    CONSTRAINT chk_story_complete CHECK (completed_at IS NULL OR
        (TRIM(title) <> '' AND TRIM(content) <> '' AND TRIM(mission_instruction) <> ''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Backfill books created before this migration. New books are initialized by FamilyService.
INSERT INTO chapter(book_id, name, created_by)
SELECT b.id, '초등학생 시절', b.created_by FROM book b;
