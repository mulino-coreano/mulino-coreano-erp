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
        Instant createdAt,
        int version,
        Long governanceActionId
) {
    public AttentionDto(long attentionRequestId, String caseRef, String reasonType, String title, String question,
                        String consequence, String status, Instant createdAt) {
        this(attentionRequestId, caseRef, reasonType, title, question, consequence, status, createdAt, 1, null);
    }
}
