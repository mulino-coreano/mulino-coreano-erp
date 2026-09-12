package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;

public record CaseDto(
        long caseId,
        String caseRef,
        String title,
        String objective,
        String status,
        String intentType,
        Instant openedAt
) {}
