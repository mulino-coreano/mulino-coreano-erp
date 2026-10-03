package com.mulinocoreano.backend.followup;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

public record ReplenishmentFollowupDto(
        String ref,
        String caseRef,
        String planRef,
        String sourceWorkItemRef,
        String workItemRef,
        String parentWorkItemRef,
        String agentKey,
        String observationStatus,
        OffsetDateTime dueAt,
        Long attentionRequestId,
        String attentionStatus,
        OffsetDateTime observedAt,
        JsonNode observation,
        boolean serverManaged) {}
