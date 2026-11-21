package com.cheongchun.common.auth;

import com.cheongchun.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

@Component
public class ParticipantContext {
    public static final String COOKIE_NAME = "participant_session";
    public static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final HttpServletRequest request;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ParticipantContext(HttpServletRequest request, JdbcClient jdbc, Clock clock) {
        this.request = request;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long currentParticipantId() {
        return currentParticipant().participantId();
    }

    public ParticipantIdentity currentParticipant() {
        return findCurrentParticipant().orElseThrow(ApiException::sessionRequired);
    }

    public Optional<ParticipantIdentity> findCurrentParticipant() {
        var cookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (cookie == null || !TOKEN_PATTERN.matcher(cookie.getValue()).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT p.id, p.display_name, p.birth_year
                FROM participant_session s JOIN participant p ON p.id = s.participant_id
                WHERE s.token_hash = :tokenHash AND s.revoked_at IS NULL AND s.expires_at > :now
                """).param("tokenHash", hash(cookie.getValue())).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((row, index) -> new ParticipantIdentity(row.getLong("id"), row.getString("display_name"),
                        row.getObject("birth_year", Integer.class))).optional();
    }

    public String issueSession(long participantId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                INSERT INTO participant_session (participant_id, token_hash, expires_at)
                VALUES (:participantId, :tokenHash, :expiresAt)
                """).param("participantId", participantId).param("tokenHash", hash(token))
                .param("expiresAt", clock.instant().plus(SESSION_LIFETIME).atOffset(ZoneOffset.UTC)).update();
        return token;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public record ParticipantIdentity(long participantId, String displayName, Integer birthYear) {}
}
