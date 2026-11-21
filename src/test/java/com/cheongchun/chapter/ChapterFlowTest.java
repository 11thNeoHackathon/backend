package com.cheongchun.chapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChapterFlowTest {
    private static final EmbeddedPostgres database = startDatabase();
    private static final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> database.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        database.close();
    }

    @Test
    void sharedChapterKeepsOtherStoryPrivateAndUnlocksOnlyTheMission() throws Exception {
        String parent = cookie(call("POST", "/participants", null, "{\"displayName\":\"엄마\"}"));
        var book = call("POST", "/books", parent, "{\"counterpartLabel\":\"자녀\"}");
        long bookId = number(book, "$.data.book.bookId");
        String invitation = JsonPath.read(book.body(), "$.data.invitation.code");
        var initial = call("GET", "/books/" + bookId + "/chapters", parent, null);
        assertThat(initial.statusCode()).isEqualTo(200);
        assertThat(initial.body()).contains("초등학생 시절");
        long chapterId = number(initial, "$.data[0].id");
        String chapter = "/chapters/" + chapterId;

        assertThat(call("POST", chapter + "/story/complete", parent, null).statusCode()).isEqualTo(400);
        String child = cookie(call("POST", "/invitations/" + invitation + "/accept", null,
                "{\"displayName\":\"자녀\",\"counterpartLabel\":\"엄마\"}"));
        String outsider = cookie(call("POST", "/participants", null, "{\"displayName\":\"아빠\"}"));
        assertThat(call("GET", chapter, outsider, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", chapter, null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/books/" + bookId + "/chapters", parent, "{\"name\":\" \"}")
                .statusCode()).isEqualTo(400);

        String parentStory = "{\"title\":\"첫 월급\",\"content\":\"엄마의 비밀 추억\","
                + "\"missionInstruction\":\"부모님 노래 듣기\"}";
        assertThat(call("PUT", chapter + "/story", parent, parentStory).statusCode()).isEqualTo(200);
        var childBefore = call("GET", chapter, child, null);
        assertThat(childBefore.body()).doesNotContain("엄마의 비밀 추억", "부모님 노래 듣기");
        assertThat(call("DELETE", chapter, child, null).statusCode()).isEqualTo(409);
        assertThat(call("POST", chapter + "/story/complete", parent, null).body()).contains("WAIT");
        assertThat(call("POST", chapter + "/story/complete", parent, null).statusCode()).isEqualTo(200);

        String childStory = "{\"title\":\"대학 첫날\",\"content\":\"자녀의 비밀 추억\","
                + "\"missionInstruction\":\"학교 산책하기\"}";
        call("PUT", chapter + "/story", child, childStory);
        var completed = call("POST", chapter + "/story/complete", child, null);
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(completed.body()).contains("DO_MISSION", "부모님 노래 듣기")
                .doesNotContain("엄마의 비밀 추억");
        assertThat((Object) JsonPath.read(completed.body(), "$.data.counterpartStory")).isNull();
        assertThat((Object) JsonPath.read(completed.body(), "$.data.openedAt")).isNull();
        assertThat(call("GET", chapter, parent, null).body())
                .contains("학교 산책하기").doesNotContain("자녀의 비밀 추억");
        assertThat(call("PUT", chapter + "/story", child, "{}").statusCode()).isEqualTo(400);
        assertThat(call("PUT", chapter + "/story", parent, parentStory).statusCode()).isEqualTo(200);

        var empty = call("POST", "/books/" + bookId + "/chapters", child, "{\"name\":\"빈 챕터\"}");
        assertThat(empty.statusCode()).isEqualTo(201);
        long emptyId = number(empty, "$.data.id");
        assertThat(call("DELETE", "/chapters/" + emptyId, parent, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/chapters/" + emptyId, child, null).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> call(String method, String path, String cookie, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        request.header("Content-Type", "application/json").method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static long number(HttpResponse<String> response, String path) {
        return ((Number) JsonPath.read(response.body(), path)).longValue();
    }

    private static EmbeddedPostgres startDatabase() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
