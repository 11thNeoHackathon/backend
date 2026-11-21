package com.cheongchun.common.auth;

import com.cheongchun.common.api.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class BookAccessService {
    private final JdbcClient jdbc;

    public BookAccessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BookParticipant requireParticipant(long bookId, long participantId) {
        return jdbc.sql("""
                SELECT id, book_id, participant_id, slot_no, counterpart_label, joined_at
                FROM book_participant WHERE book_id = :bookId AND participant_id = :participantId
                """).param("bookId", bookId).param("participantId", participantId)
                .query((row, index) -> new BookParticipant(row.getLong("id"), row.getLong("book_id"),
                        row.getLong("participant_id"), row.getInt("slot_no"), row.getString("counterpart_label"),
                        row.getObject("joined_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional().orElseThrow(BookAccessService::bookNotFound);
    }

    public static ApiException bookNotFound() {
        return ApiException.notFound("BOOK_NOT_FOUND", "책을 찾을 수 없어요.");
    }
}
