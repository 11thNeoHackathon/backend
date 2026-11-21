package com.cheongchun.chapter;

import java.time.Instant;
import java.util.List;

final class ChapterModels {
    private ChapterModels() {}

    record CreateChapter(String name) {}
    record WriteStory(String title, String content, String missionInstruction) {}
    record WriteReflection(String content) {}
    record Chapter(long id, long bookId, String name, Instant openedAt) {}
    record Story(long id, long authorId, String title, String content, String missionInstruction,
                 Instant completedAt, Instant updatedAt) {}
    record Reflection(long id, long participantId, String content, Instant completedAt, Instant updatedAt) {}
    record Progress(long participantId, boolean storyCompleted, boolean reflectionCompleted) {}
    record ChapterView(long id, long bookId, String name, String nextAction, Instant openedAt,
                       List<Progress> progress, Story myStory, Story counterpartStory,
                       String receivedMission, Reflection myReflection, Reflection counterpartReflection) {}
}
