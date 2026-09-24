package com.mulinocoreano.backend.common.error;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "CMN001", "Invalid input value"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "CMN002", "Malformed JSON request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "CMN003", "Validation failed"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "CMN004", "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "CMN009", "Conflict"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CMN503", "Service unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "CMN500", "Internal server error");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override public HttpStatus getStatus() { return status; }
    @Override public String getCode() { return code; }
    @Override public String getMessage() { return message; }
}