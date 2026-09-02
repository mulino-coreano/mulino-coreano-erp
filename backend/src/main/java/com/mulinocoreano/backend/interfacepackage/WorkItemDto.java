package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;

public record WorkItemDto(
        long workItemId,
        String workItemRef,
        String title,
        String status,
        String assignedAgent,
        String waitingReason,
        Instant dueAt
) {}
