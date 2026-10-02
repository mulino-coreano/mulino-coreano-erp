package com.mulinocoreano.backend.planning;

import com.mulinocoreano.backend.interfacepackage.AttentionDto;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

public record PlanDto(String ref, String caseRef, int version, long warehouseId, Instant asOf,
                      int horizonDays, LocalDate targetDate, JsonNode sourceSnapshot, JsonNode result,
                      String sourceHash, String hash, AttentionDto attention) {}
