package com.cheongchun.family;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = FamilyController.class)
class FamilyErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(FamilyErrorHandler.class);

    @ExceptionHandler(FamilyException.class)
    ResponseEntity<ErrorBody> knownError(FamilyException error) {
        return response(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    ResponseEntity<ErrorBody> invalidInput() {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력 형식을 확인해 주세요.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> unsupportedMediaType() {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "JSON 형식으로 보내 주세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception error) {
        log.error("Unexpected family API error", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했어요.");
    }

    private ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorBody(code, message));
    }

    record ErrorBody(String code, String message) {}
}
