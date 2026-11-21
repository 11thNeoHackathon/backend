package com.cheongchun.chapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChapterFlowTest {
    private static final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> testVariable("TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> testVariable("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> testVariable("TEST_DB_PASSWORD"));
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
        assertThat(call("PUT", chapter + "/reflection", parent, "{\"content\":\"아직 안 됨\"}")
                .statusCode()).isEqualTo(409);

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
        String changedMission = parentStory.replace("부모님 노래 듣기", "다른 노래 듣기");
        var rejectedMissionEdit = call("PUT", chapter + "/story", parent, changedMission);
        assertThat(rejectedMissionEdit.statusCode()).isEqualTo(409);
        assertThat(rejectedMissionEdit.body()).contains("MISSION_LOCKED");
        assertThat(call("PUT", chapter + "/reflection", outsider, "{\"content\":\"접근 불가\"}")
                .statusCode()).isEqualTo(404);
        assertThat(call("PUT", chapter + "/reflection", parent, "{\"content\":\" \"}")
                .statusCode()).isEqualTo(400);

        var parentReflected = call("PUT", chapter + "/reflection", parent,
                "{\"content\":\"학교를 걸으니 추억이 났다\"}");
        assertThat(parentReflected.statusCode()).isEqualTo(200);
        assertThat(parentReflected.body()).contains("WAIT_FOR_OPEN", "학교를 걸으니 추억이 났다");
        var childWhileClosed = call("GET", chapter, child, null);
        assertThat(childWhileClosed.body()).doesNotContain("학교를 걸으니 추억이 났다", "엄마의 비밀 추억");
        assertThat((Object) JsonPath.read(childWhileClosed.body(), "$.data.counterpartReflection")).isNull();
        assertThat((Boolean) JsonPath.read(childWhileClosed.body(), "$.data.progress[0].reflectionCompleted"))
                .isTrue();
        assertThat(call("PUT", chapter + "/reflection", parent,
                "{\"content\":\"학교 산책을 다시 해 봤다\"}").statusCode()).isEqualTo(200);
        assertThat(call("PUT", chapter + "/story", child,
                childStory.replace("학교 산책하기", "다른 곳 가기")).statusCode()).isEqualTo(409);

        var opened = call("PUT", chapter + "/reflection", child,
                "{\"content\":\"노래를 들으며 엄마를 떠올렸다\"}");
        assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(opened.body()).contains("READ", "엄마의 비밀 추억", "학교 산책을 다시 해 봤다");
        assertThat((Object) JsonPath.read(opened.body(), "$.data.openedAt")).isNotNull();
        assertThat(call("GET", chapter, parent, null).body())
                .contains("자녀의 비밀 추억", "노래를 들으며 엄마를 떠올렸다");
        assertThat(call("PUT", chapter + "/reflection", parent,
                "{\"content\":\"뒤늦은 수정\"}").statusCode()).isEqualTo(409);
        assertThat(call("PUT", chapter + "/story", child, childStory).statusCode()).isEqualTo(409);
        assertThat(call("DELETE", chapter, child, null).statusCode()).isEqualTo(409);

        var empty = call("POST", "/books/" + bookId + "/chapters", child, "{\"name\":\"빈 챕터\"}");
        assertThat(empty.statusCode()).isEqualTo(201);
        long emptyId = number(empty, "$.data.id");
        assertThat(call("DELETE", "/chapters/" + emptyId, parent, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/chapters/" + emptyId, child, null).statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentReflectionsOpenTheChapterOnceBothAreSaved() throws Exception {
        String first = cookie(call("POST", "/participants", null, "{\"displayName\":\"동시 엄마\"}"));
        var book = call("POST", "/books", first, "{\"counterpartLabel\":\"자녀\"}");
        long bookId = number(book, "$.data.book.bookId");
        String code = JsonPath.read(book.body(), "$.data.invitation.code");
        String second = cookie(call("POST", "/invitations/" + code + "/accept", null,
                "{\"displayName\":\"동시 자녀\"}"));
        long chapterId = number(call("GET", "/books/" + bookId + "/chapters", first, null), "$.data[0].id");
        String path = "/chapters/" + chapterId;
        String story = "{\"title\":\"좋았던 날\",\"content\":\"함께 걷던 길\","
                + "\"missionInstruction\":\"노래 듣기\"}";
        for (String cookie : new String[] {first, second}) {
            assertThat(call("PUT", path + "/story", cookie, story).statusCode()).isEqualTo(200);
            assertThat(call("POST", path + "/story/complete", cookie, null).statusCode()).isEqualTo(200);
        }

        var one = http.sendAsync(request("PUT", path + "/reflection", first,
                "{\"content\":\"첫 번째 감상\"}"), HttpResponse.BodyHandlers.ofString());
        var two = http.sendAsync(request("PUT", path + "/reflection", second,
                "{\"content\":\"두 번째 감상\"}"), HttpResponse.BodyHandlers.ofString());
        assertThat(one.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(two.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        var opened = call("GET", path, first, null);
        assertThat(opened.body()).contains("READ", "두 번째 감상");
        assertThat((Object) JsonPath.read(opened.body(), "$.data.openedAt")).isNotNull();
        assertThat(call("GET", path, second, null).body()).contains("첫 번째 감상");
    }

    private HttpResponse<String> call(String method, String path, String cookie, String body) throws Exception {
        return http.send(request(method, path, cookie, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String cookie, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        request.header("Content-Type", "application/json").method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return request.build();
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static long number(HttpResponse<String> response, String path) {
        return ((Number) JsonPath.read(response.body(), path)).longValue();
    }

    private static String testVariable(String name) {
        String value = System.getenv(name);
        if (value == null || (!name.equals("TEST_DB_PASSWORD") && value.isBlank())) {
            throw new IllegalStateException("Set " + name + " for an isolated MySQL integration-test database");
        }
        return value;
    }
}
