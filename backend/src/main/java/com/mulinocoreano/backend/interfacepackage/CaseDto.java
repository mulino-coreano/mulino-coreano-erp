package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record CaseDto(
        long caseId,
        String caseRef,
        String title,
        String objective,
        String status,
        String intentType,
        Instant openedAt,
        JsonNode metadata,
        boolean reused
) {
    public CaseDto(long caseId,String caseRef,String title,String objective,String status,String intentType,Instant openedAt) {
        this(caseId,caseRef,title,objective,status,intentType,openedAt,null,false);
    }
    public CaseDto reusedReceipt() { return new CaseDto(caseId,caseRef,title,objective,status,intentType,openedAt,metadata,true); }
}
