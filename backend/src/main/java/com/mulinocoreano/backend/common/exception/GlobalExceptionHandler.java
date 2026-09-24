package com.mulinocoreano.backend.common.exception;

import com.mulinocoreano.backend.common.error.ApiError;
import com.mulinocoreano.backend.common.error.CommonErrorCode;
import com.mulinocoreano.backend.common.error.ErrorCode;
import com.mulinocoreano.backend.common.error.FieldError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.info("Validation failed: {}", errors);
        return ResponseEntity
                .status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(ApiError.of(CommonErrorCode.VALIDATION_FAILED, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException e) {
        log.info("Malformed HttpMessageNotReadableException: {}", e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.MALFORMED_JSON.getStatus())
                .body(ApiError.of(CommonErrorCode.MALFORMED_JSON));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException e) {
        log.info("Invalid input: {}", e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ApiError.of(CommonErrorCode.INVALID_INPUT_VALUE, e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return declaredStatus(status, e.getReason());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception e) {
        ResponseStatus declared = AnnotatedElementUtils.findMergedAnnotation(
                e.getClass(), ResponseStatus.class);
        if (declared != null && declared.code() != HttpStatus.INTERNAL_SERVER_ERROR) {
            String message = declared.reason().isBlank() ? e.getMessage() : declared.reason();
            return declaredStatus(declared.code(), message);
        }
        log.error("Internal Server Error occurred", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiError.of(CommonErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ApiError> declaredStatus(HttpStatus status, String message) {
        ErrorCode code = switch (status) {
            case CONFLICT -> CommonErrorCode.CONFLICT;
            case SERVICE_UNAVAILABLE -> CommonErrorCode.SERVICE_UNAVAILABLE;
            case NOT_FOUND -> CommonErrorCode.RESOURCE_NOT_FOUND;
            case BAD_REQUEST -> CommonErrorCode.INVALID_INPUT_VALUE;
            default -> CommonErrorCode.INTERNAL_ERROR;
        };
        String safeMessage = (message == null || message.isBlank()) ? code.getMessage() : message;
        String applicationCode = code == CommonErrorCode.INTERNAL_ERROR && status != HttpStatus.INTERNAL_SERVER_ERROR
                ? "CMN" + status.value()
                : code.getCode();
        log.info("Request rejected with status {}: {}", status.value(), safeMessage);
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), applicationCode, safeMessage, null));
    }
}
