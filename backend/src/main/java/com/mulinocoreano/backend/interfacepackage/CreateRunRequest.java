package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @NotBlank(message = "agentKey is required")
        @Size(max = 50, message = "agentKey must be at most 50 characters")
        String agentKey,     // ORCHESTRATOR / SUPPLY_CHAIN / PROCUREMENT / QC / LOGISTICS
        @NotBlank(message = "caseRef is required")
        @Size(max = 20, message = "caseRef must be at most 20 characters")
        String caseRef,
        @Size(max = 20, message = "workItemRef must be at most 20 characters")
        String workItemRef,  // nullable
        @NotBlank(message = "runtime is required")
        @Pattern(regexp = "CLAUDE|CODEX", message = "runtime must be CLAUDE or CODEX")
        String runtime       // CLAUDE | CODEX
) {}
