package com.mulinocoreano.backend.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Common API Response")
public record ApiResult<T>(
        @Schema(description = "HTTP status code", example = "200")
        int status,
        @Schema(description = "Response message", example = "success")
        String message,
        @Schema(description = "Response data")
        T data
) {
    public static <T> ResponseEntity<ApiResult<T>> ok(T data) {
        return ResponseEntity.ok(new ApiResult<>(HttpStatus.OK.value(), null, data));
    }

    public static <T> ResponseEntity<ApiResult<T>> ok(T data, String message) {
        return ResponseEntity.ok(new ApiResult<>(HttpStatus.OK.value(), message, data));
    }

    public static <T> ResponseEntity<ApiResult<T>> created(T data) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ApiResult<>(HttpStatus.CREATED.value(), null, data));
    }

}