package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCaseRequest(
        @NotBlank(message = "objective is required")
        String objective,
        @Pattern(regexp = "ACT", message = "intentType must be ACT when supplied")
        String intentType,   // optional compatibility field; Case intake is always ACT
        @Pattern(regexp = "CHAT|SLACK|EMAIL|DASHBOARD|API", message = "channel is invalid")
        String channel        // CHAT | SLACK | EMAIL | DASHBOARD | API
) {}
