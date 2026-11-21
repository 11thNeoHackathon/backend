package com.cheongchun.family;

import static com.cheongchun.family.FamilyModels.*;

import com.cheongchun.common.api.ApiException;
import com.cheongchun.common.auth.BookAccessService;
import com.cheongchun.common.auth.ParticipantContext;
import com.cheongchun.common.auth.ParticipantContext.ParticipantIdentity;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FamilyService {
    private static final Duration INVITE_LIFETIME = Duration.ofHours(24);
    private static final Pattern INVITE_CODE = Pattern.compile("[A-Za-z0-9_-]{32}");

    private final FamilyStore store;
    private final ParticipantContext participants;
    private final BookAccessService bookAccess;
    private final Clock clock;
    private final String inviteBaseUrl;
    private final SecureRandom random = new SecureRandom();

    FamilyService(FamilyStore store, ParticipantContext participants, BookAccessService bookAccess, Clock clock,
                  @Value("${app.invite-base-url}") String inviteBaseUrl) {
        this.store = store;
        this.participants = participants;
        this.bookAccess = bookAccess;
        this.clock = clock;
        this.inviteBaseUrl = inviteBaseUrl.replaceFirst("/+$", "");
    }

    @Transactional
    public Reply<ParticipantIdentity> register(ProfileInput input) {
        Optional<ParticipantIdentity> existing = participants.findCurrentParticipant();
        if (existing.isPresent()) {
            return new Reply<>(existing.get(), null, false);
        }
        return registerParticipant(input.displayName(), input.birthYear());
    }

    ParticipantIdentity me() {
        return participants.currentParticipant();
    }

    @Transactional
    public CreatedBook createBook(CreateBookInput input) {
        long creatorId = participants.currentParticipantId();
        String title = optionalText(input.title(), 100, "책 제목", "청춘 교환일기");
        String label = requiredText(input.counterpartLabel(), 30, "상대 호칭");
        long bookId = store.createBook(creatorId, title);
        store.addMember(bookId, creatorId, 1, label);
        String code = randomToken(24);
        Instant expiresAt = clock.instant().plus(INVITE_LIFETIME);
        store.createInvite(bookId, creatorId, code, expiresAt);
        return new CreatedBook(bookFor(creatorId, bookId), new InvitationLink(code,
                inviteBaseUrl + "/invite/" + code, expiresAt));
    }

    List<BookView> listBooks() {
        return store.listBooks(participants.currentParticipantId());
    }

    BookView getBook(long bookId) {
        if (bookId < 1) {
            throw ApiException.invalidRequest("책 번호를 확인해 주세요.");
        }
        long participantId = participants.currentParticipantId();
        bookAccess.requireParticipant(bookId, participantId);
        return bookFor(participantId, bookId);
    }

    InvitePreview previewInvite(String code) {
        Invite invite = invite(code, false);
        if (invite.acceptedBy() != null) {
            boolean linked = participants.findCurrentParticipant().filter(p ->
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
    public Reply<BookView> acceptInvite(String code, AcceptInput input) {
        // A locking read sees the current invitation after waiting for a concurrent accept.
        Invite invite = invite(code, true);
        Optional<ParticipantIdentity> existing = participants.findCurrentParticipant();
        if (existing.filter(p -> p.participantId() == invite.issuedBy()).isPresent()) {
            throw ApiException.conflict("SELF_INVITATION", "내가 만든 초대는 수락할 수 없어요.");
        }
        if (invite.acceptedBy() != null) {
            ParticipantIdentity acceptor = existing.filter(p -> p.participantId() == invite.acceptedBy())
                    .orElseThrow(FamilyService::alreadyUsed);
            return new Reply<>(bookFor(acceptor.participantId(), invite.bookId()), null, false);
        }
        checkAvailable(invite);
        String label = optionalText(input.counterpartLabel(), 30, "상대 호칭", "가족");
        Reply<ParticipantIdentity> member = existing.map(p -> new Reply<>(p, null, false))
                .orElseGet(() -> registerParticipant(input.displayName(), input.birthYear()));
        store.addMember(invite.bookId(), member.data().participantId(), 2, label);
        store.acceptInvite(invite.id(), member.data().participantId(), clock.instant());
        return new Reply<>(bookFor(member.data().participantId(), invite.bookId()), member.newSessionToken(), true);
    }

    private Reply<ParticipantIdentity> registerParticipant(String displayName, Integer birthYear) {
        String name = requiredText(displayName, 50, "닉네임");
        if (birthYear != null && (birthYear < 1900 || birthYear > Year.now(clock).getValue())) {
            throw ApiException.invalidRequest("출생연도를 확인해 주세요.");
        }
        ParticipantIdentity participant = store.createParticipant(name, birthYear);
        String token = participants.issueSession(participant.participantId());
        return new Reply<>(participant, token, true);
    }

    private BookView bookFor(long participantId, long bookId) {
        return store.findBook(participantId, bookId).orElseThrow(BookAccessService::bookNotFound);
    }

    private Invite invite(String code, boolean lockBook) {
        if (INVITE_CODE.matcher(code).matches()) {
            return store.findInvite(code, lockBook).orElseThrow(FamilyService::inviteNotFound);
        }
        throw inviteNotFound();
    }

    private void checkAvailable(Invite invite) {
        if (invite.revokedAt() != null) {
            throw ApiException.conflict("INVITATION_REVOKED", "사용할 수 없는 초대 링크예요.");
        }
        if (!invite.expiresAt().isAfter(clock.instant())) {
            throw ApiException.conflict("INVITATION_EXPIRED", "초대 링크가 만료됐어요.");
        }
    }

    private static ApiException alreadyUsed() {
        return ApiException.conflict("INVITATION_USED", "이미 다른 기기가 이 초대를 수락했어요.");
    }

    private static ApiException inviteNotFound() {
        return ApiException.notFound("INVITATION_NOT_FOUND", "초대 링크를 찾을 수 없어요.");
    }

    private static String requiredText(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidRequest(label + " 값을 입력해 주세요.");
        }
        String text = value.strip();
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw ApiException.invalidRequest(label + "은 " + maxLength + "자 이하여야 해요.");
        }
        if (text.indexOf('\0') >= 0) {
            throw ApiException.invalidRequest(label + "에 사용할 수 없는 문자가 있어요.");
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

}
