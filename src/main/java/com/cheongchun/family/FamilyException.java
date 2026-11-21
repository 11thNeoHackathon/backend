package com.cheongchun.family;

import org.springframework.http.HttpStatus;

final class FamilyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    FamilyException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }

    static FamilyException invalid(String message) {
        return new FamilyException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    static FamilyException conflict(String code, String message) {
        return new FamilyException(HttpStatus.CONFLICT, code, message);
    }
}
