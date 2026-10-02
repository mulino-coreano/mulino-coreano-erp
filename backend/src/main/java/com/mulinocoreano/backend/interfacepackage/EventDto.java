package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;
import java.util.Map;

public record EventDto(
        long eventId,
        String eventType,
        String externalRef,
        String caseRef,
        String workItemRef,
        Map<String, Object> payload,
        Instant occurredAt
) {
}
