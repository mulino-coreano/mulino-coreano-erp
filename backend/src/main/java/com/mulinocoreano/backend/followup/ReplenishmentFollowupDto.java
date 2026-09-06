package com.mulinocoreano.backend.followup;

import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

public record ReplenishmentFollowupDto(
        String ref, String caseRef, String planRef, String sourceWorkItemRef,
        String workItemRef, String parentWorkItemRef, String agentKey,
        String observationStatus, OffsetDateTime dueAt, Long attentionRequestId,
        String attentionStatus, OffsetDateTime observedAt, JsonNode observation,
        boolean serverManaged) {}
