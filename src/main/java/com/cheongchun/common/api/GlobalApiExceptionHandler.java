package com.cheongchun.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> knownError(ApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure(error.code(), error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        log.error("Unexpected API error", error);
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure("INTERNAL_ERROR", "요청을 처리하지 못했어요."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception error, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ApiResponse<Void> response = switch (status.value()) {
            case 400 -> ApiResponse.failure("INVALID_REQUEST", "입력 형식을 확인해 주세요.");
            case 401 -> ApiResponse.failure("SESSION_REQUIRED", "먼저 닉네임을 입력해 주세요.");
            case 403 -> ApiResponse.failure("FORBIDDEN", "이 작업을 할 권한이 없어요.");
            case 404 -> ApiResponse.failure("NOT_FOUND", "요청한 데이터를 찾을 수 없어요.");
            case 405 -> ApiResponse.failure("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식이에요.");
            case 415 -> ApiResponse.failure("UNSUPPORTED_MEDIA_TYPE", "JSON 형식으로 보내 주세요.");
            default -> status.is5xxServerError()
                    ? ApiResponse.failure("INTERNAL_ERROR", "요청을 처리하지 못했어요.")
                    : ApiResponse.failure("REQUEST_FAILED", "요청을 처리하지 못했어요.");
        };
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setCacheControl(CacheControl.noStore());
        if (status.isSameCodeAs(HttpStatus.INTERNAL_SERVER_ERROR)) {
            log.error("Unexpected MVC error", error);
        }
        return super.handleExceptionInternal(error, response, responseHeaders, status, request);
    }
}
