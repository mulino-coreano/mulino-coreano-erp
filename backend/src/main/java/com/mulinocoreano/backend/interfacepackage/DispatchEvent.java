package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;
import java.util.Map;

public record DispatchEvent(
        String eventType,
        Long caseId,
        Long workItemId,
        Map<String, Object> payload,
        Instant occurredAt
) { }
