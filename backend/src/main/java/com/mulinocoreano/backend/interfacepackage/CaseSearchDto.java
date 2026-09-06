package com.mulinocoreano.backend.interfacepackage;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/**
 * Existing Case fields remain top-level; summary is an additive read projection. Results are capped
 * at 100.
 */
public record CaseSearchDto(
        long caseId,
        String caseRef,
        String title,
        String objective,
        String status,
        String intentType,
        Instant openedAt,
        JsonNode metadata,
        boolean reused,
        Map<String, Object> summary) {}
