package com.mulinocoreano.backend.procurement;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

public record PurchasePlan(
        long id,
        long caseId,
        long warehouse,
        String ref,
        int version,
        LocalDate target,
        JsonNode source,
        JsonNode result,
        String sourceHash,
        String hash,
        String caseRef,
        JsonNode metadata,
        Long requester,
        String caseStatus,
        boolean current) {}
