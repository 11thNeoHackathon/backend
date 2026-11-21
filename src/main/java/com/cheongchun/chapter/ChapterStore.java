package com.cheongchun.chapter;

import static com.cheongchun.chapter.ChapterModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
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
        return jdbc.sql("""
                INSERT INTO chapter(book_id, created_by, name) VALUES (:bookId, :creatorId, :name)
                RETURNING id
                """).param("bookId", bookId).param("creatorId", creatorId).param("name", name)
                .query(Long.class).single();
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
                ON CONFLICT(chapter_id, author_id) DO UPDATE
                SET title = EXCLUDED.title, content = EXCLUDED.content,
                    mission_instruction = EXCLUDED.mission_instruction, updated_at = CURRENT_TIMESTAMP
                """).param("chapterId", chapterId).param("authorId", authorId)
                .param("title", input.title()).param("content", input.content())
                .param("mission", input.missionInstruction()).update();
    }

    void complete(long chapterId, long authorId) {
        jdbc.sql("""
                UPDATE story SET completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
                                 updated_at = CURRENT_TIMESTAMP
                WHERE chapter_id = :chapterId AND author_id = :authorId
                """).param("chapterId", chapterId).param("authorId", authorId).update();
    }

    void delete(long chapterId) {
        jdbc.sql("DELETE FROM chapter WHERE id = :id").param("id", chapterId).update();
    }

    private static Chapter chapter(ResultSet row, int index) throws SQLException {
        return new Chapter(row.getLong("id"), row.getLong("book_id"), row.getString("name"),
                instant(row, "opened_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
