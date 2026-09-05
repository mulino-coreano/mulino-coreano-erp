package com.mulinocoreano.backend.execution;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Stable, non-secret protocol errors shared by worker finish and scoped agent transitions. */
@RestControllerAdvice(assignableTypes = {RunExecutionController.class, AgentWorkController.class})
public class ExecutionErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> rejected(ResponseStatusException failure) {
        String reason = failure.getReason();
        String code = reason != null && reason.matches("[A-Z_]+") ? reason : "RUN_REQUEST_REJECTED";
        return ResponseEntity.status(failure.getStatusCode())
                .body(Map.of("error", code, "message", reason == null ? code : reason));
    }
}
