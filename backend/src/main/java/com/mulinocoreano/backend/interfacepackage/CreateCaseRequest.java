package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record CreateCaseRequest(
        @NotBlank(message = "objective is required")
        String objective,
        @Pattern(regexp = "ACT", message = "intentType must be ACT when supplied")
        String intentType,   // optional compatibility field; Case intake is always ACT
        @Pattern(regexp = "CHAT|SLACK|EMAIL|DASHBOARD|API", message = "channel is invalid")
        String channel,       // CHAT | SLACK | EMAIL | DASHBOARD | API
        @Valid Replenishment replenishment
) {
    public CreateCaseRequest(String objective, String intentType, String channel) { this(objective, intentType, channel, null); }
    public record Replenishment(@NotEmpty @Size(max=100) List<@NotBlank @Size(max=50) String> productSkus,
                                @Positive Long warehouseId, LocalDate targetDate) {}
}
