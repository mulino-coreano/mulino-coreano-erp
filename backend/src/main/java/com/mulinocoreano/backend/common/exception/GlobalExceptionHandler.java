package com.mulinocoreano.backend.common.exception;


import com.mulinocoreano.backend.common.response.ApiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice

public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal Argument: {}",e.getMessage());
        return ApiResult.badRequest(e.getMessage());
    }

    @ExceptionHandler(value = Exception.class)
    public ResponseEntity<ApiResult<Void>> handleException(Exception e) {
        log.error("Internal Server Error occurred", e);
        return ApiResult.internalServerError();
    }
}