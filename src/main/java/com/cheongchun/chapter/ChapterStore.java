package com.cheongchun.chapter;

import static com.cheongchun.chapter.ChapterModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class ChapterStore {
    private final JdbcClient jdbc;

    ChapterStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Chapter> list(long bookId) {
        return jdbc.sql("SELECT id, book_id, name, opened_at FROM chapter WHERE book_id = :bookId ORDER BY id")
                .param("bookId", bookId).query(ChapterStore::chapter).list();
    }

    Optional<Chapter> find(long id, boolean lock) {
        return jdbc.sql("SELECT id, book_id, name, opened_at FROM chapter WHERE id = :id"
                        + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(ChapterStore::chapter).optional();
    }

    long create(long bookId, long creatorId, String name) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO chapter(book_id, created_by, name) VALUES (:bookId, :creatorId, :name)
                """).param("bookId", bookId).param("creatorId", creatorId).param("name", name)
                .update(key, "id");
        return Objects.requireNonNull(key.getKey(), "Chapter ID was not generated").longValue();
    }

    List<Long> members(long bookId) {
        return jdbc.sql("SELECT participant_id FROM book_participant WHERE book_id = :id ORDER BY slot_no")
                .param("id", bookId).query(Long.class).list();
    }

    List<Story> stories(long chapterId) {
        return jdbc.sql("""
                SELECT id, author_id, title, content, mission_instruction, completed_at, updated_at
                FROM story WHERE chapter_id = :id ORDER BY id
                """).param("id", chapterId).query((row, index) -> new Story(row.getLong("id"),
                        row.getLong("author_id"), row.getString("title"), row.getString("content"),
                        row.getString("mission_instruction"), instant(row, "completed_at"),
                        instant(row, "updated_at"))).list();
    }

    void write(long chapterId, long authorId, WriteStory input) {
        jdbc.sql("""
                INSERT INTO story(chapter_id, author_id, title, content, mission_instruction)
                VALUES (:chapterId, :authorId, :title, :content, :mission)
                ON DUPLICATE KEY UPDATE
                    title = :title, content = :content,
                    mission_instruction = :mission, updated_at = CURRENT_TIMESTAMP(6)
                """).param("chapterId", chapterId).param("authorId", authorId)
                .param("title", input.title()).param("content", input.content())
                .param("mission", input.missionInstruction()).update();
    }

    void complete(long chapterId, long authorId) {
        jdbc.sql("""
                UPDATE story SET completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(6)),
                                 updated_at = CURRENT_TIMESTAMP(6)
                WHERE chapter_id = :chapterId AND author_id = :authorId
                """).param("chapterId", chapterId).param("authorId", authorId).update();
    }

    List<Reflection> reflections(long chapterId) {
        return jdbc.sql("""
                SELECT id, participant_id, content, completed_at, updated_at
                FROM reflection WHERE chapter_id = :id ORDER BY id
                """).param("id", chapterId).query((row, index) -> new Reflection(row.getLong("id"),
                        row.getLong("participant_id"), row.getString("content"),
                        instant(row, "completed_at"), instant(row, "updated_at"))).list();
    }

    void writeReflection(long chapterId, long participantId, String content) {
        jdbc.sql("""
                INSERT INTO reflection(chapter_id, participant_id, content)
                VALUES (:chapterId, :participantId, :content)
                ON DUPLICATE KEY UPDATE
                    content = :content, updated_at = CURRENT_TIMESTAMP(6)
                """).param("chapterId", chapterId).param("participantId", participantId)
                .param("content", content).update();
    }

    void open(long chapterId) {
        jdbc.sql("UPDATE chapter SET opened_at = CURRENT_TIMESTAMP(6) WHERE id = :id AND opened_at IS NULL")
                .param("id", chapterId).update();
    }

    void delete(long chapterId) {
        jdbc.sql("DELETE FROM chapter WHERE id = :id").param("id", chapterId).update();
    }

    private static Chapter chapter(ResultSet row, int index) throws SQLException {
        return new Chapter(row.getLong("id"), row.getLong("book_id"), row.getString("name"),
                instant(row, "opened_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
