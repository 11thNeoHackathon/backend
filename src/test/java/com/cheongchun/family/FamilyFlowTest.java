package com.cheongchun.family;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FamilyFlowTest {
    private static final EmbeddedPostgres database = startPostgres();
    private static final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> database.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        database.close();
    }

    @Test
    void twoParentsConnectAndADifferentFamilyCannotSeeTheBook() throws Exception {
        var parent = post("/participants", null, """
                {"displayName":"엄마", "birthYear":1976}
                """);
        assertThat(parent.statusCode()).isEqualTo(201);
        assertThat(parent.headers().firstValue("Set-Cookie").orElseThrow())
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=");
        String parentCookie = cookie(parent);
        assertThat(get("/participants/me", parentCookie).body()).contains("엄마");
        assertThat(post("/participants", parentCookie, "{\"displayName\":\"새 이름\"}").statusCode()).isEqualTo(200);

        var created = createBook(parentCookie, "아빠");
        Number bookId = value(created, "$.data.book.bookId");
        String code = value(created, "$.data.invitation.code");
        assertThat((String) value(created, "$.data.book.status")).isEqualTo("INVITE_PENDING");
        String invite = "/invitations/" + code;
        assertThat(get(invite, null).body()).contains("AVAILABLE", "엄마");
        assertThat(post(invite + "/accept", parentCookie, "{}").body()).contains("SELF_INVITATION");
        assertThat(post(invite + "/accept", null, "{}").statusCode()).isEqualTo(400);

        var accepted = post(invite + "/accept", null, "{\"displayName\":\"아빠\",\"counterpartLabel\":\"엄마\"}");
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat((Number) value(accepted, "$.data.bookId")).isEqualTo(bookId);
        assertThat((Integer) value(accepted, "$.data.mySlotNo")).isEqualTo(2);
        assertThat(accepted.body()).contains("ACTIVE", "엄마");
        String acceptorCookie = cookie(accepted);
        assertThat(post(invite + "/accept", acceptorCookie, "{}").statusCode()).isEqualTo(200);
        assertThat(get(invite, acceptorCookie).body()).contains("LINKED");
        assertThat(get("/books/" + bookId, parentCookie).body()).contains("ACTIVE", "아빠");

        var outsider = post("/participants", null, "{\"displayName\":\"삼촌\"}");
        String outsiderCookie = cookie(outsider);
        assertThat(post(invite + "/accept", outsiderCookie, "{}").body()).contains("INVITATION_USED");
        assertThat(get("/books/" + bookId, outsiderCookie).statusCode()).isEqualTo(404);
        assertThat((List<?>) value(get("/books", outsiderCookie), "$.data")).isEmpty();
        createBook(parentCookie, "할머니");
        assertThat((List<?>) value(get("/books", parentCookie), "$.data")).hasSize(2);
    }

    @Test
    void concurrentAcceptsCreateOnlyOneSecondMember() throws Exception {
        String creator = cookie(post("/participants", null, "{\"displayName\":\"초대자\"}"));
        var created = createBook(creator, "가족");
        Number bookId = value(created, "$.data.book.bookId");
        String code = value(created, "$.data.invitation.code");
        String path = "/invitations/" + code + "/accept";
        var a = http.sendAsync(request(path, null, "{\"displayName\":\"상대 A\"}"), HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(request(path, null, "{\"displayName\":\"상대 B\"}"), HttpResponse.BodyHandlers.ofString());
        var first = a.get(20, TimeUnit.SECONDS);
        var second = b.get(20, TimeUnit.SECONDS);
        assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(first.statusCode() == 409 ? first.body() : second.body()).contains("INVITATION_USED");
        assertThat(jdbc.sql("SELECT count(*) FROM book_participant WHERE book_id = :id")
                .param("id", bookId.longValue()).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void malformedRequestsAndExpiredLinksHaveDefinedErrors() throws Exception {
        assertThat(get("/books", null).statusCode()).isEqualTo(401);
        assertThat(post("/participants", null, "{broken").statusCode()).isEqualTo(400);
        assertThat(post("/participants", null, "{\"displayName\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(get("/invitations/nonexistent", null).statusCode()).isEqualTo(404);
        String creator = cookie(post("/participants", null, "{\"displayName\":\"초대자\"}"));
        String code = value(createBook(creator, "가족"), "$.data.invitation.code");
        jdbc.sql("UPDATE book_invitation SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE code = :code")
                .param("code", code).update();
        var response = post("/invitations/" + code + "/accept", null, "{\"displayName\":\"상대\"}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("INVITATION_EXPIRED");
        assertThat(get("/books/not-a-number", creator).statusCode()).isEqualTo(400);
    }

    @Test
    void swaggerUiUsesTheSharedContract() throws Exception {
        var config = http.send(HttpRequest.newBuilder(uri("/v3/api-docs/swagger-config")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.body()).contains("/openapi/server1.yaml");
        var contract = http.send(HttpRequest.newBuilder(uri("/openapi/server1.yaml")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(contract.statusCode()).isEqualTo(200);
        assertThat(contract.body()).contains("/api/v1/invitations/{code}/accept:");
    }

    private HttpResponse<String> createBook(String cookie, String label) throws Exception {
        var response = post("/books", cookie, "{\"counterpartLabel\":\"" + label + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return response;
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return http.send(request(path, cookie, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String cookie, String body) throws Exception {
        return http.send(request(path, cookie, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String cookie, String body) {
        var builder = HttpRequest.newBuilder(uri("/api/v1" + path)).timeout(Duration.ofSeconds(15));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return body == null ? builder.GET().build() : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static <T> T value(HttpResponse<String> response, String path) {
        return JsonPath.read(response.body(), path);
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
