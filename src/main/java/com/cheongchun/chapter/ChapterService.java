package com.cheongchun.chapter;

import static com.cheongchun.chapter.ChapterModels.*;

import com.cheongchun.common.api.ApiException;
import com.cheongchun.common.auth.BookAccessService;
import com.cheongchun.common.auth.ParticipantContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChapterService {
    private final ChapterStore store;
    private final ParticipantContext participants;
    private final BookAccessService access;

    ChapterService(ChapterStore store, ParticipantContext participants, BookAccessService access) {
        this.store = store;
        this.participants = participants;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<ChapterView> list(long bookId) {
        long me = participants.currentParticipantId();
        access.requireParticipant(bookId, me);
        return store.list(bookId).stream().map(chapter -> view(chapter, me)).toList();
    }

    @Transactional
    public ChapterView create(long bookId, CreateChapter input) {
        long me = participants.currentParticipantId();
        access.requireParticipant(bookId, me);
        String name = text(input.name(), 100, true, "챕터 이름");
        long id = store.create(bookId, me, name);
        return view(store.find(id, false).orElseThrow(), me);
    }

    @Transactional(readOnly = true)
    public ChapterView get(long chapterId) {
        long me = participants.currentParticipantId();
        return view(authorized(chapterId, me, false), me);
    }

    @Transactional
    public ChapterView write(long chapterId, WriteStory input) {
        long me = participants.currentParticipantId();
        Chapter chapter = authorized(chapterId, me, true);
        requireClosed(chapter);
        WriteStory clean = new WriteStory(text(input.title(), 200, false, "이야기 제목"),
                text(input.content(), 20000, false, "이야기"),
                text(input.missionInstruction(), 2000, false, "미션"));
        Story current = ownStory(chapterId, me);
        if (current != null && current.completedAt() != null) {
            text(clean.title(), 200, true, "이야기 제목");
            text(clean.content(), 20000, true, "이야기");
            text(clean.missionInstruction(), 2000, true, "미션");
        }
        store.write(chapterId, me, clean);
        return view(chapter, me);
    }

    @Transactional
    public ChapterView complete(long chapterId) {
        long me = participants.currentParticipantId();
        Chapter chapter = authorized(chapterId, me, true);
        requireClosed(chapter);
        if (store.members(chapter.bookId()).size() != 2) {
            throw ApiException.invalidRequest("가족 연결을 마친 뒤 이야기를 완료해 주세요.");
        }
        Story story = ownStory(chapterId, me);
        if (story == null) {
            throw ApiException.invalidRequest("이야기와 미션을 먼저 작성해 주세요.");
        }
        text(story.title(), 200, true, "이야기 제목");
        text(story.content(), 20000, true, "이야기");
        text(story.missionInstruction(), 2000, true, "미션");
        store.complete(chapterId, me);
        return view(chapter, me);
    }

    @Transactional
    public void delete(long chapterId) {
        long me = participants.currentParticipantId();
        Chapter chapter = authorized(chapterId, me, true);
        requireClosed(chapter);
        boolean hasWriting = store.stories(chapterId).stream().anyMatch(story ->
                !story.title().isBlank() || !story.content().isBlank()
                        || !story.missionInstruction().isBlank() || story.completedAt() != null);
        if (hasWriting) {
            throw ApiException.conflict("CHAPTER_NOT_EMPTY", "작성한 내용이 있는 챕터는 삭제할 수 없어요.");
        }
        store.delete(chapterId);
    }

    private Chapter authorized(long chapterId, long me, boolean lock) {
        Chapter chapter = store.find(chapterId, lock).orElseThrow(() ->
                ApiException.notFound("CHAPTER_NOT_FOUND", "챕터를 찾을 수 없어요."));
        access.requireParticipant(chapter.bookId(), me);
        return chapter;
    }

    private ChapterView view(Chapter chapter, long me) {
        List<Story> stories = store.stories(chapter.id());
        Story own = stories.stream().filter(s -> s.authorId() == me).findFirst().orElse(null);
        Story peer = stories.stream().filter(s -> s.authorId() != me).findFirst().orElse(null);
        List<Progress> progress = store.members(chapter.bookId()).stream().map(id -> new Progress(id,
                stories.stream().anyMatch(s -> s.authorId() == id && s.completedAt() != null))).toList();
        boolean ownDone = own != null && own.completedAt() != null;
        boolean peerDone = peer != null && peer.completedAt() != null;
        String nextAction = "WRITE_STORY";
        if (chapter.openedAt() != null) {
            nextAction = "READ";
        } else if (ownDone) {
            nextAction = peerDone ? "DO_MISSION" : "WAIT";
        }
        return new ChapterView(chapter.id(), chapter.bookId(), chapter.name(), nextAction,
                chapter.openedAt(), progress, own, chapter.openedAt() == null ? null : peer,
                ownDone && peerDone ? peer.missionInstruction() : null);
    }

    private Story ownStory(long chapterId, long me) {
        return store.stories(chapterId).stream().filter(s -> s.authorId() == me).findFirst().orElse(null);
    }

    private static void requireClosed(Chapter chapter) {
        if (chapter.openedAt() != null) {
            throw ApiException.conflict("CHAPTER_OPENED", "열린 챕터는 수정하거나 삭제할 수 없어요.");
        }
    }

    private static String text(String value, int max, boolean required, String label) {
        String result = value == null ? "" : value.strip();
        if ((required && result.isBlank()) || result.codePointCount(0, result.length()) > max
                || result.indexOf(0) >= 0) {
            throw ApiException.invalidRequest(label + " 입력값을 확인해 주세요.");
        }
        return result;
    }
}
