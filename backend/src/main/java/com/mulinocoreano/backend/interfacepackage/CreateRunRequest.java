package com.mulinocoreano.backend.interfacepackage;

public record CreateRunRequest(
        String agentKey,     // ORCHESTRATOR / SUPPLY_CHAIN / PROCUREMENT / QC / LOGISTICS
        String caseRef,
        String workItemRef,  // nullable
        String runtime       // CLAUDE | CODEX
) {}
