package com.cheongchun.common.auth;

import java.time.Instant;

public record BookParticipant(long id, long bookId, long participantId, int slotNo, String counterpartLabel,
                              Instant joinedAt) {}
