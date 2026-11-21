package com.cheongchun.chapter;

import static com.cheongchun.chapter.ChapterModels.*;

import com.cheongchun.common.api.ApiResponse;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ChapterController {
    private final ChapterService chapters;

    ChapterController(ChapterService chapters) {
        this.chapters = chapters;
    }

    @GetMapping("/books/{bookId}/chapters")
    ResponseEntity<ApiResponse<List<ChapterView>>> list(@PathVariable long bookId) {
        return ok(chapters.list(bookId));
    }

    @PostMapping("/books/{bookId}/chapters")
    ResponseEntity<ApiResponse<ChapterView>> create(@PathVariable long bookId, @RequestBody CreateChapter input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(chapters.create(bookId, input)));
    }

    @GetMapping("/chapters/{chapterId}")
    ResponseEntity<ApiResponse<ChapterView>> get(@PathVariable long chapterId) {
        return ok(chapters.get(chapterId));
    }

    @PutMapping("/chapters/{chapterId}/story")
    ResponseEntity<ApiResponse<ChapterView>> write(@PathVariable long chapterId, @RequestBody WriteStory input) {
        return ok(chapters.write(chapterId, input));
    }

    @PostMapping("/chapters/{chapterId}/story/complete")
    ResponseEntity<ApiResponse<ChapterView>> complete(@PathVariable long chapterId) {
        return ok(chapters.complete(chapterId));
    }

    @PutMapping("/chapters/{chapterId}/reflection")
    ResponseEntity<ApiResponse<ChapterView>> reflect(@PathVariable long chapterId,
                                                      @RequestBody WriteReflection input) {
        return ok(chapters.reflect(chapterId, input));
    }

    @DeleteMapping("/chapters/{chapterId}")
    ResponseEntity<ApiResponse<Void>> delete(@PathVariable long chapterId) {
        chapters.delete(chapterId);
        return ok(null);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
