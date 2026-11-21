package com.cheongchun.family;

import static com.cheongchun.family.FamilyModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FamilyStore {
    private static final String BOOKS_FOR_PARTICIPANT = """
            SELECT b.id, b.title, me.slot_no, me.counterpart_label,
                   other.display_name AS counterpart_name, peer.participant_id AS counterpart_id
            FROM book_participant me
            JOIN book b ON b.id = me.book_id
            LEFT JOIN book_participant peer ON peer.book_id = me.book_id AND peer.slot_no <> me.slot_no
            LEFT JOIN participant other ON other.id = peer.participant_id
            WHERE me.participant_id = :participantId
            """;

    private final JdbcClient jdbc;

    FamilyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Participant> participantBySession(String tokenHash, Instant now) {
        return jdbc.sql("""
                SELECT p.id, p.display_name, p.birth_year
                FROM participant_session s JOIN participant p ON p.id = s.participant_id
                WHERE s.token_hash = :tokenHash AND s.revoked_at IS NULL AND s.expires_at > :now
                """).param("tokenHash", tokenHash).param("now", utc(now))
                .query((row, index) -> new Participant(row.getLong("id"), row.getString("display_name"),
                        row.getObject("birth_year", Integer.class))).optional();
    }

    Participant createParticipant(String displayName, Integer birthYear) {
        long id = jdbc.sql("""
                INSERT INTO participant (display_name, birth_year) VALUES (:displayName, :birthYear) RETURNING id
                """).param("displayName", displayName).param("birthYear", birthYear)
                .query(Long.class).single();
        return new Participant(id, displayName, birthYear);
    }

    void createSession(long participantId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO participant_session (participant_id, token_hash, expires_at)
                VALUES (:participantId, :tokenHash, :expiresAt)
                """).param("participantId", participantId).param("tokenHash", tokenHash)
                .param("expiresAt", utc(expiresAt)).update();
    }

    long createBook(long creator, String title) {
        return jdbc.sql("INSERT INTO book (created_by, title) VALUES (:creator, :title) RETURNING id")
                .param("creator", creator).param("title", title).query(Long.class).single();
    }

    void addMember(long bookId, long participantId, int slotNo, String counterpartLabel) {
        jdbc.sql("""
                INSERT INTO book_participant (book_id, participant_id, slot_no, counterpart_label)
                VALUES (:bookId, :participantId, :slotNo, :counterpartLabel)
                """).param("bookId", bookId).param("participantId", participantId).param("slotNo", slotNo)
                .param("counterpartLabel", counterpartLabel).update();
    }

    List<BookView> listBooks(long participantId) {
        return jdbc.sql(BOOKS_FOR_PARTICIPANT + " ORDER BY b.id DESC")
                .param("participantId", participantId).query(FamilyStore::mapBook).list();
    }

    Optional<BookView> findBook(long participantId, long bookId) {
        return jdbc.sql(BOOKS_FOR_PARTICIPANT + " AND b.id = :bookId")
                .param("participantId", participantId).param("bookId", bookId).query(FamilyStore::mapBook).optional();
    }

    void createInvite(long bookId, long issuedBy, String code, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO book_invitation (book_id, issued_by, code, expires_at)
                VALUES (:bookId, :issuedBy, :code, :expiresAt)
                """).param("bookId", bookId).param("issuedBy", issuedBy)
                .param("code", code).param("expiresAt", utc(expiresAt)).update();
    }

    Optional<Invite> findInvite(String code, boolean lockBook) {
        return jdbc.sql("""
                SELECT i.id, i.book_id, i.issued_by, i.accepted_by, i.expires_at, i.revoked_at,
                       p.display_name AS inviter_name, b.title AS book_title
                FROM book_invitation i
                JOIN book b ON b.id = i.book_id
                JOIN participant p ON p.id = i.issued_by
                WHERE i.code = :code
                """ + (lockBook ? " FOR UPDATE OF b" : ""))
                .param("code", code).query((row, index) -> new Invite(
                        row.getLong("id"), row.getLong("book_id"), row.getLong("issued_by"),
                        row.getObject("accepted_by", Long.class), instant(row, "expires_at"),
                        instant(row, "revoked_at"), row.getString("inviter_name"), row.getString("book_title")))
                .optional();
    }

    void acceptInvite(long inviteId, long participantId, Instant now) {
        jdbc.sql("""
                UPDATE book_invitation SET accepted_by = :participantId, accepted_at = :now WHERE id = :inviteId
                """).param("participantId", participantId).param("now", utc(now)).param("inviteId", inviteId).update();
    }

    private static BookView mapBook(ResultSet row, int index) throws SQLException {
        String status = row.getObject("counterpart_id", Long.class) == null ? "INVITE_PENDING" : "ACTIVE";
        return new BookView(row.getLong("id"), row.getString("title"), status, row.getInt("slot_no"),
                row.getString("counterpart_label"), row.getString("counterpart_name"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
