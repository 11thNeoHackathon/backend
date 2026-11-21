package com.cheongchun.family;

import static com.cheongchun.family.FamilyModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FamilyService {
    static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final Duration INVITE_LIFETIME = Duration.ofHours(24);
    private static final Pattern COOKIE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern INVITE_CODE = Pattern.compile("[A-Za-z0-9_-]{32}");

    private final FamilyStore store;
    private final Clock clock;
    private final String inviteBaseUrl;
    private final SecureRandom random = new SecureRandom();

    FamilyService(FamilyStore store, Clock clock, @Value("${app.invite-base-url}") String inviteBaseUrl) {
        this.store = store;
        this.clock = clock;
        this.inviteBaseUrl = inviteBaseUrl.replaceFirst("/+$", "");
    }

    @Transactional
    public Reply<Participant> register(String sessionToken, ProfileInput input) {
        Optional<Participant> existing = participant(sessionToken);
        if (existing.isPresent()) {
            return new Reply<>(existing.get(), null, false);
        }
        return registerParticipant(input.displayName(), input.birthYear());
    }

    Participant me(String token) {
        return participant(token).orElseThrow(() -> new FamilyException(
                HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "먼저 닉네임을 입력해 주세요."));
    }

    @Transactional
    public CreatedBook createBook(String token, CreateBookInput input) {
        Participant creator = me(token);
        String title = optionalText(input.title(), 100, "책 제목", "청춘 교환일기");
        String label = requiredText(input.counterpartLabel(), 30, "상대 호칭");
        long bookId = store.createBook(creator.participantId(), title);
        store.addMember(bookId, creator.participantId(), 1, label);
        String code = randomToken(24);
        Instant expiresAt = clock.instant().plus(INVITE_LIFETIME);
        store.createInvite(bookId, creator.participantId(), code, expiresAt);
        return new CreatedBook(bookFor(creator, bookId), new InvitationLink(code,
                inviteBaseUrl + "/invite/" + code, expiresAt));
    }

    List<BookView> listBooks(String token) {
        return store.listBooks(me(token).participantId());
    }

    BookView getBook(String token, long bookId) {
        if (bookId < 1) {
            throw FamilyException.invalid("책 번호를 확인해 주세요.");
        }
        return bookFor(me(token), bookId);
    }

    InvitePreview previewInvite(String token, String code) {
        Invite invite = invite(code, false);
        if (invite.acceptedBy() != null) {
            boolean linked = participant(token).filter(p ->
                    store.findBook(p.participantId(), invite.bookId()).isPresent()).isPresent();
            if (!linked) {
                throw alreadyUsed();
            }
            return new InvitePreview("LINKED", invite.inviterName(), invite.bookTitle(),
                    invite.expiresAt(), invite.bookId());
        }
        checkAvailable(invite);
        return new InvitePreview("AVAILABLE", invite.inviterName(), invite.bookTitle(), invite.expiresAt(), null);
    }

    @Transactional
    public Reply<BookView> acceptInvite(String token, String code, AcceptInput input) {
        // A locking read sees the current invitation after waiting for a concurrent accept.
        Invite invite = invite(code, true);
        Optional<Participant> existing = participant(token);
        if (existing.filter(p -> p.participantId() == invite.issuedBy()).isPresent()) {
            throw FamilyException.conflict("SELF_INVITATION", "내가 만든 초대는 수락할 수 없어요.");
        }
        if (invite.acceptedBy() != null) {
            Participant acceptor = existing.filter(p -> p.participantId() == invite.acceptedBy())
                    .orElseThrow(FamilyService::alreadyUsed);
            return new Reply<>(bookFor(acceptor, invite.bookId()), null, false);
        }
        checkAvailable(invite);
        String label = optionalText(input.counterpartLabel(), 30, "상대 호칭", "가족");
        Reply<Participant> member = existing.map(p -> new Reply<>(p, null, false))
                .orElseGet(() -> registerParticipant(input.displayName(), input.birthYear()));
        store.addMember(invite.bookId(), member.data().participantId(), 2, label);
        store.acceptInvite(invite.id(), member.data().participantId(), clock.instant());
        return new Reply<>(bookFor(member.data(), invite.bookId()), member.newSessionToken(), true);
    }

    private Reply<Participant> registerParticipant(String displayName, Integer birthYear) {
        String name = requiredText(displayName, 50, "닉네임");
        if (birthYear != null && (birthYear < 1900 || birthYear > Year.now(clock).getValue())) {
            throw FamilyException.invalid("출생연도를 확인해 주세요.");
        }
        Participant participant = store.createParticipant(name, birthYear);
        String token = randomToken(32);
        store.createSession(participant.participantId(), hash(token), clock.instant().plus(SESSION_LIFETIME));
        return new Reply<>(participant, token, true);
    }

    private Optional<Participant> participant(String token) {
        return token != null && COOKIE_TOKEN.matcher(token).matches()
                ? store.participantBySession(hash(token), clock.instant()) : Optional.empty();
    }

    private BookView bookFor(Participant participant, long bookId) {
        return store.findBook(participant.participantId(), bookId).orElseThrow(() -> new FamilyException(
                HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "책을 찾을 수 없어요."));
    }

    private Invite invite(String code, boolean lockBook) {
        if (INVITE_CODE.matcher(code).matches()) {
            return store.findInvite(code, lockBook).orElseThrow(FamilyService::inviteNotFound);
        }
        throw inviteNotFound();
    }

    private void checkAvailable(Invite invite) {
        if (invite.revokedAt() != null) {
            throw FamilyException.conflict("INVITATION_REVOKED", "사용할 수 없는 초대 링크예요.");
        }
        if (!invite.expiresAt().isAfter(clock.instant())) {
            throw FamilyException.conflict("INVITATION_EXPIRED", "초대 링크가 만료됐어요.");
        }
    }

    private static FamilyException alreadyUsed() {
        return FamilyException.conflict("INVITATION_USED", "이미 다른 기기가 이 초대를 수락했어요.");
    }

    private static FamilyException inviteNotFound() {
        return new FamilyException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", "초대 링크를 찾을 수 없어요.");
    }

    private static String requiredText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw FamilyException.invalid(label + " 값을 입력해 주세요.");
        }
        String text = value.strip();
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw FamilyException.invalid(label + "은 " + maxLength + "자 이하여야 해요.");
        }
        if (text.indexOf('\0') >= 0) {
            throw FamilyException.invalid(label + "에 사용할 수 없는 문자가 있어요.");
        }
        return text;
    }

    private static String optionalText(String value, int maxLength, String label, String fallback) {
        return value == null ? fallback : requiredText(value, maxLength, label);
    }

    private String randomToken(int bytes) {
        byte[] randomBytes = new byte[bytes];
        random.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
