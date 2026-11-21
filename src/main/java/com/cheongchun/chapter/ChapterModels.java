package com.cheongchun.chapter;

import java.time.Instant;
import java.util.List;

final class ChapterModels {
    private ChapterModels() {}

    record CreateChapter(String name) {}
    record WriteStory(String title, String content, String missionInstruction) {}
    record Chapter(long id, long bookId, String name, Instant openedAt) {}
    record Story(long id, long authorId, String title, String content, String missionInstruction,
                 Instant completedAt, Instant updatedAt) {}
    record Progress(long participantId, boolean storyCompleted) {}
    record ChapterView(long id, long bookId, String name, String nextAction, Instant openedAt,
                       List<Progress> progress, Story myStory, Story counterpartStory,
                       String receivedMission) {}
}
