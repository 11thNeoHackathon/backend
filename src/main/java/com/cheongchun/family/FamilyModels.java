package com.cheongchun.family;

import java.time.Instant;

final class FamilyModels {
    private FamilyModels() {}

    record ProfileInput(String displayName, Integer birthYear) {}
    record CreateBookInput(String title, String counterpartLabel) {}
    record AcceptInput(String displayName, Integer birthYear, String counterpartLabel) {}

    record Participant(long participantId, String displayName, Integer birthYear) {}
    record BookView(long bookId, String title, String status, int mySlotNo, String counterpartLabel,
                    String counterpartName) {}
    record InvitationLink(String code, String inviteUrl, Instant expiresAt) {}
    record CreatedBook(BookView book, InvitationLink invitation) {}
    record InvitePreview(String status, String inviterName, String bookTitle, Instant expiresAt, Long bookId) {}
    record Invite(long id, long bookId, long issuedBy, Long acceptedBy, Instant expiresAt, Instant revokedAt,
                  String inviterName, String bookTitle) {}
    record Reply<T>(T data, String newSessionToken, boolean created) {}
}
