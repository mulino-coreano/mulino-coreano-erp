package com.mulinocoreano.backend.procurement;

import jakarta.validation.constraints.*;

public record PurchaseDecisionRequest(
        @NotNull @Pattern(regexp = "APPROVE|BLOCK") String decision,
        @NotNull @Positive Integer expectedVersion,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String proposalHash,
        @NotBlank @Size(max = 4000) String reason) {}
