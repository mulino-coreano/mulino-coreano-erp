package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;

public record AttentionDto(
        long attentionRequestId,
        String caseRef,
        String reasonType,
        String title,
        String question,
        String consequence,
        String status,
        Instant createdAt
) {}
