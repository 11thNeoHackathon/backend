CREATE TABLE participant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    display_name VARCHAR(50) NOT NULL,
    birth_year SMALLINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_participant_display_name CHECK (TRIM(display_name) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE participant_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    participant_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_participant_session_participant FOREIGN KEY (participant_id) REFERENCES participant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE book (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_by BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL DEFAULT '청춘 교환일기',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_book_creator FOREIGN KEY (created_by) REFERENCES participant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE book_participant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    slot_no SMALLINT NOT NULL,
    counterpart_label VARCHAR(30) NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (book_id, participant_id),
    UNIQUE (book_id, slot_no),
    INDEX idx_book_participant_participant (participant_id, book_id),
    CONSTRAINT fk_book_participant_book FOREIGN KEY (book_id) REFERENCES book(id),
    CONSTRAINT fk_book_participant_participant FOREIGN KEY (participant_id) REFERENCES participant(id),
    CONSTRAINT chk_book_participant_slot CHECK (slot_no IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE book_invitation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    issued_by BIGINT NOT NULL,
    accepted_by BIGINT,
    code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6),
    revoked_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_book_invitation_book FOREIGN KEY (book_id) REFERENCES book(id),
    CONSTRAINT fk_book_invitation_issuer FOREIGN KEY (book_id, issued_by)
        REFERENCES book_participant(book_id, participant_id),
    CONSTRAINT fk_book_invitation_acceptor FOREIGN KEY (book_id, accepted_by)
        REFERENCES book_participant(book_id, participant_id),
    CONSTRAINT chk_book_invitation_accepted CHECK ((accepted_by IS NULL) = (accepted_at IS NULL)),
    CONSTRAINT chk_book_invitation_no_self_accept CHECK (accepted_by IS NULL OR accepted_by <> issued_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
