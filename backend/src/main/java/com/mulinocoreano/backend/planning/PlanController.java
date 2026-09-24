package com.mulinocoreano.backend.planning;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class PlanController {
    private final PlanPersistenceService plans;

    public PlanController(PlanPersistenceService plans) { this.plans = plans; }

    @PostMapping("/cases/{caseRef}/plans")
    public PlanDto calculate(@PathVariable String caseRef, @Valid @RequestBody PlanRequest request,
                             @RequestHeader("Idempotency-Key") String key,
                             @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        String token = authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7) : "";
        return plans.calculate(caseRef, request, key, token);
    }

    @GetMapping("/plans/{planRef}")
    public PlanDto get(@PathVariable String planRef) { return plans.get(planRef); }

    @ExceptionHandler(PlanPersistenceService.Failure.class)
    public ResponseEntity<JsonNode> failure(PlanPersistenceService.Failure failure) {
        return ResponseEntity.status(failure.status()).body(failure.body());
    }
}
