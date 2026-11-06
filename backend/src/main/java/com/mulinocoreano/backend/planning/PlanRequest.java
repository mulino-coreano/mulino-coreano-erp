package com.mulinocoreano.backend.planning;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** The server supplies asOf from its planning clock; callers only choose the planning scope. */
public record PlanRequest(@NotNull @Positive Long warehouseId,
                          @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> productIds,
                          @Min(1) @Max(90) Integer horizonDays) {
    @JsonAnySetter
    public void rejectUnknown(String field, JsonNode ignored) {
        throw new IllegalArgumentException("UNKNOWN_PLANNING_FIELD");
    }
}
