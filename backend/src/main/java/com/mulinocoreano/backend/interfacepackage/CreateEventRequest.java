package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateEventRequest(
        @NotBlank(message = "eventType is required")
        @Size(max = 100, message = "eventType must be at most 100 characters")
        String eventType,
        @NotBlank(message = "externalRef is required")
        @Size(max = 255, message = "externalRef must be at most 255 characters")
        String externalRef,
        @Size(max = 20, message = "caseRef must be at most 20 characters")
        String caseRef,
        @Size(max = 20, message = "workItemRef must be at most 20 characters")
        String workItemRef,
        Map<String, Object> payload
) {
}
