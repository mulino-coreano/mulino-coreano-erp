package com.mulinocoreano.backend.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Common API Error Response")
public record ApiError(
        @Schema(description = "HTTP status code", example = "400")
        int status,
        @Schema(description = "Application error code", example = "CMN001")
        String code,
        @Schema(description = "Error message", example = "Invalid input value")
        String message,
        @Schema(description = "Field-level errors (validation only)")
        List<FieldError> errors
) {
    public static ApiError of(ErrorCode errorCode) {
        return new ApiError(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ApiError of(ErrorCode errorCode, String message) {
        return new ApiError(errorCode.getStatus().value(), errorCode.getCode(), message, null);
    }

    public static ApiError of(ErrorCode errorCode, List<FieldError> errors) {
        return new ApiError(errorCode.getStatus().value(), errorCode.getCode(), errorCode.getMessage(), errors);
    }
}