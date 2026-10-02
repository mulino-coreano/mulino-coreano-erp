package com.mulinocoreano.backend.planning;

import com.mulinocoreano.backend.execution.RunCapabilityAccess;
import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.interfacepackage.AttentionDto;
import com.mulinocoreano.backend.persistence.PlanningDataGuard;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only planning evidence, with authorization and idempotency in the same ERP snapshot
 * transaction.
 */
@Service
public class PlanPersistenceService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_ATTEMPTS = 4;
    private final PlanPersistenceRepository repository;
    private final PlanningSnapshotRepository snapshots;
    private final ReplenishmentCalculator calculator;
    private final RunCapabilityAccess capabilities;
    private final RequestIdempotency idempotency;
    private final CanonicalJson json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final PlanningDataGuard dataGuard;
    private final PlanQueryRepository planQueries;

    public PlanPersistenceService(
            PlanPersistenceRepository repository,
            PlanningSnapshotRepository snapshots,
            ReplenishmentCalculator calculator,
            RunCapabilityAccess capabilities,
            RequestIdempotency idempotency,
            CanonicalJson json,
            ObjectMapper mapper,
            @Qualifier("planningClock") Clock clock,
            PlatformTransactionManager transactionManager,
            PlanningDataGuard dataGuard,
            PlanQueryRepository planQueries) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.calculator = calculator;
        this.capabilities = capabilities;
        this.idempotency = idempotency;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.dataGuard = dataGuard;
        this.planQueries = planQueries;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(30);
    }

    public PlanDto calculate(
            String caseRef, PlanRequest request, String key, String capabilityToken) {
        PlanRequest normalized = normalize(request);
        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response =
                        transaction.execute(
                                status -> {
                                    dataGuard.lock();
                                    // Intake uses this same order before touching the warehouse or
                                    // its Case.
                                    idempotency.coordinate(
                                            "planning.warehouse",
                                            String.valueOf(normalized.warehouseId()));
                                    var scope =
                                            capabilities.requireLocked(
                                                    capabilityToken, "SUPPLY_CHAIN", caseRef);
                                    JsonNode humanScope = requireCase(scope);
                                    String namespace =
                                            "plans:" + caseRef + ":" + scope.workItemRef();
                                    var stored =
                                            idempotency.execute(
                                                    namespace,
                                                    key,
                                                    normalized,
                                                    () -> {
                                                        Object receipt =
                                                                prepare(
                                                                        scope,
                                                                        normalized,
                                                                        humanScope);
                                                        recordAttempt(scope, receipt);
                                                        return receipt;
                                                    });
                                    // The lease can expire during calculation. Throwing here rolls
                                    // back the plan,
                                    // Attention and idempotency row together; replays also require
                                    // a live lease.
                                    capabilities.requireLocked(
                                            capabilityToken, "SUPPLY_CHAIN", caseRef);
                                    return stored;
                                });
                if (response.has("error")) throw new Failure(HttpStatus.CONFLICT, response);
                return mapper.treeToValue(response, PlanDto.class);
            } catch (RuntimeException exception) {
                if (!retryable(exception)) throw exception;
                if (attempt == MAX_ATTEMPTS)
                    throw failure(HttpStatus.CONFLICT, "PLANNING_CONCURRENT_CHANGE");
                // Retry only after TransactionTemplate has rolled back, with an entirely new
                // snapshot.
            }
        }
    }

    public PlanDto get(String planRef) {
        return planQueries
                .find(planRef)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND"));
    }

    private Object prepare(
            RunCapabilityAccess.RunScope scope, PlanRequest request, JsonNode humanScope) {
        var asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate date = asOf.atZone(SEOUL).toLocalDate();
        Integer scopedHorizon = null;
        if (humanScope != null) {
            CaseScope target;
            try {
                target = caseScope(humanScope);
            } catch (IllegalArgumentException invalid) {
                return dataFailure(scope, "INVALID_CASE_REPLENISHMENT_SCOPE");
            }
            if (request.warehouseId() != target.warehouse())
                throw failure(HttpStatus.FORBIDDEN, "CASE_WAREHOUSE_SCOPE_MISMATCH");
            if (!request.productIds().equals(target.products()))
                throw failure(HttpStatus.FORBIDDEN, "CASE_PRODUCT_SCOPE_MISMATCH");
            long days = ChronoUnit.DAYS.between(date, target.targetDate()) + 1;
            if (days <= 0) return dataFailure(scope, "CASE_TARGET_DATE_PASSED");
            if (days > 90) return dataFailure(scope, "CASE_TARGET_DATE_OUT_OF_RANGE");
            scopedHorizon = (int) days;
            if (request.horizonDays() != null && !request.horizonDays().equals(scopedHorizon))
                throw failure(HttpStatus.FORBIDDEN, "CASE_TARGET_DATE_SCOPE_MISMATCH");
        }
        long warehouse = request.warehouseId();
        if (!repository.warehouseExists(warehouse))
            throw failure(HttpStatus.NOT_FOUND, "WAREHOUSE_NOT_FOUND");
        if (repository.productCount(request.productIds()) != request.productIds().size())
            throw failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        bindPlanningCase(scope, warehouse);
        var policyHorizon = repository.policyHorizon(warehouse);
        if (policyHorizon.isEmpty()) return dataFailure(scope, "MISSING_PLANNING_POLICY");
        int horizon =
                scopedHorizon != null
                        ? scopedHorizon
                        : request.horizonDays() == null
                                ? policyHorizon.get()
                                : request.horizonDays();
        PlanningSnapshotRepository.Snapshot snapshot;
        try {
            snapshot = snapshots.load(warehouse, request.productIds(), date, horizon);
        } catch (IllegalArgumentException invalidData) {
            // A reconciliation failure did not produce a coherent Snapshot. Keep a visible,
            // replayable Attention, never a fabricated or partially populated plan snapshot.
            return dataFailure(scope, code(invalidData));
        }
        ObjectNode result;
        try {
            result = mapper.valueToTree(calculator.calculate(snapshot));
        } catch (IllegalArgumentException invalidData) {
            result = mapper.createObjectNode();
            result.put("status", "NEEDS_ATTENTION");
            result.set("forecasts", mapper.createObjectNode());
            result.putNull("requirements");
            result.set("purchases", mapper.createArrayNode());
            result.putNull("totalAmount");
            result.set(
                    "issues",
                    mapper.valueToTree(
                            List.of(
                                    new ReplenishmentCalculator.Issue(
                                            code(invalidData), "planning-input"))));
        }
        AttentionDto attention = null;
        if ("NEEDS_ATTENTION".equals(result.path("status").asText())) {
            String code = result.path("issues").get(0).path("code").asText();
            attention = createAttention(scope, code);
            result.set("attention", mapper.valueToTree(attention));
        }
        int version = repository.nextVersion(scope.caseId());
        String sourceHash = json.sha256(snapshot);
        String planHash =
                json.sha256(
                        Map.of(
                                "caseRef",
                                scope.caseRef(),
                                "version",
                                version,
                                "result",
                                result,
                                "sourceHash",
                                sourceHash));
        String ref = "PLAN-" + UUID.randomUUID();
        LocalDate target = date.plusDays(horizon - 1L);
        repository.insertPlan(
                ref,
                scope.caseId(),
                warehouse,
                version,
                asOf,
                horizon,
                target,
                json.write(snapshot),
                json.write(result),
                sourceHash,
                planHash,
                scope.workItemId());
        return new PlanDto(
                ref,
                scope.caseRef(),
                version,
                warehouse,
                asOf,
                horizon,
                target,
                json.readTree(json.write(snapshot)),
                result,
                sourceHash,
                planHash,
                attention);
    }

    private JsonNode requireCase(RunCapabilityAccess.RunScope scope) {
        var rows = repository.lockCase(scope.caseRef());
        if (rows.isEmpty()) throw failure(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND");
        if (rows.getFirst().id() != scope.caseId())
            throw failure(HttpStatus.FORBIDDEN, "CASE_SCOPE_MISMATCH");
        if (!Set.of("OPEN", "IN_PROGRESS", "WAITING").contains(rows.getFirst().status()))
            throw failure(HttpStatus.CONFLICT, "CASE_NOT_ACTIVE");
        boolean sameWork =
                repository.workBelongsToCase(
                        scope.workItemId(), scope.caseId(), scope.workItemRef());
        if (!sameWork) throw failure(HttpStatus.FORBIDDEN, "WORK_ITEM_SCOPE_MISMATCH");
        return rows.getFirst().replenishment();
    }

    private static CaseScope caseScope(JsonNode value) {
        JsonNode warehouse = value.path("warehouseId");
        JsonNode products = value.path("productIds");
        JsonNode target = value.path("targetDate");
        if (!value.isObject()
                || !warehouse.isIntegralNumber()
                || !warehouse.canConvertToLong()
                || warehouse.asLong() <= 0
                || !products.isArray()
                || products.isEmpty()
                || !target.isString()) throw new IllegalArgumentException("Invalid Case scope");
        var ids = new java.util.ArrayList<Long>();
        for (JsonNode product : products) {
            if (!product.isIntegralNumber() || !product.canConvertToLong() || product.asLong() <= 0)
                throw new IllegalArgumentException("Invalid Case product scope");
            ids.add(product.asLong());
        }
        return new CaseScope(
                warehouse.asLong(),
                ids.stream().distinct().sorted().toList(),
                LocalDate.parse(target.asString()));
    }

    private void bindPlanningCase(RunCapabilityAccess.RunScope scope, long warehouse) {
        repository.insertPlanningCase(scope.caseId(), warehouse);
        var binding = repository.lockPlanningCase(scope.caseId());
        if (binding.isEmpty()) {
            var existing = repository.activeCaseRef(warehouse);
            if (existing.isEmpty()) throw new FreshSnapshotRequired();
            throw new Failure(
                    HttpStatus.CONFLICT,
                    mapper.valueToTree(
                            Map.of(
                                    "error",
                                    "ACTIVE_WAREHOUSE_PLAN_EXISTS",
                                    "existingCaseRef",
                                    existing.get())));
        }
        if (binding.get().warehouse() != warehouse)
            throw failure(HttpStatus.CONFLICT, "CASE_WAREHOUSE_MISMATCH");
        if (!"ACTIVE".equals(binding.get().status()))
            throw failure(HttpStatus.CONFLICT, "PLANNING_CASE_NOT_ACTIVE");
        // A row lock alone does not refresh a repeatable-read snapshot. Touch the binding
        // so concurrent calculations get 40001 and recompute max(version) in a fresh transaction.
        repository.touchPlanningCase(scope.caseId());
    }

    private Map<String, Object> dataFailure(RunCapabilityAccess.RunScope scope, String code) {
        return Map.of("error", code, "attention", createAttention(scope, code));
    }

    private void recordAttempt(RunCapabilityAccess.RunScope scope, Object receipt) {
        String outcome = "DATA_ERROR";
        Long planId = null;
        if (receipt instanceof PlanDto plan) {
            outcome = plan.result().path("status").asString();
            planId = repository.planId(plan.ref(), scope.caseId(), scope.workItemId());
        }
        int updated = repository.recordAttempt(scope.workItemId(), scope.caseId(), outcome, planId);
        if (updated != 1) throw failure(HttpStatus.FORBIDDEN, "WORK_ITEM_SCOPE_MISMATCH");
    }

    private AttentionDto createAttention(RunCapabilityAccess.RunScope scope, String code) {
        boolean missing =
                code.startsWith("MISSING_")
                        || code.equals("INSUFFICIENT_HISTORY")
                        || code.startsWith("CASE_TARGET_DATE_");
        String reason = missing ? "MISSING_HUMAN_CONTEXT" : "MATERIAL_EXCEPTION";
        String question =
                switch (code) {
                    case "INSUFFICIENT_HISTORY" -> "최소 28일의 연속 주문 이력을 확인하고 제공해 주시겠습니까?";
                    case "MISSING_ORDER_DUE_DATE" -> "미출고 확정 주문의 필요 납기일을 확인해 주시겠습니까?";
                    case "CASE_TARGET_DATE_PASSED", "CASE_TARGET_DATE_OUT_OF_RANGE" ->
                            "현재 계산할 수 있는 새 목표일을 확인해 주시겠습니까?";
                    case "NO_ELIGIBLE_SUPPLIER" -> "필요일과 인증 조건을 충족하는 공급처 또는 납기 조정 방침을 확인해 주시겠습니까?";
                    default -> "계획 입력 자료의 " + code + " 문제를 확인하고 정정해 주시겠습니까?";
                };
        String consequence = "문제가 해결되어 새 계획을 계산할 때까지 이 결과로 구매 승인을 요청하거나 발주를 진행할 수 없습니다.";
        return repository.createAttention(
                scope.caseId(),
                scope.workItemId(),
                scope.caseRef(),
                reason,
                "재보충 계획 자료 확인",
                question,
                consequence);
    }

    private static PlanRequest normalize(PlanRequest request) {
        if (request == null
                || request.warehouseId() == null
                || request.warehouseId() <= 0
                || request.productIds() == null
                || request.productIds().isEmpty()
                || request.productIds().size() > 100
                || request.productIds().stream().anyMatch(id -> id == null || id <= 0)
                || request.horizonDays() != null
                        && (request.horizonDays() < 1 || request.horizonDays() > 90))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PLANNING_REQUEST");
        return new PlanRequest(
                request.warehouseId(),
                request.productIds().stream().distinct().sorted().toList(),
                request.horizonDays());
    }

    private static String code(IllegalArgumentException exception) {
        String message = exception.getMessage();
        String code = message == null ? "" : message.split(":", 2)[0];
        return code.matches("[A-Z][A-Z0-9_]{1,80}") ? code : "INVALID_PLANNING_DATA";
    }

    private Failure failure(HttpStatus status, String code) {
        return new Failure(status, mapper.valueToTree(Map.of("error", code)));
    }

    private static boolean retryable(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof FreshSnapshotRequired) return true;
            if (current instanceof SQLException sql
                    && Set.of("40001", "40P01").contains(sql.getSQLState())) return true;
        }
        return false;
    }

    public static final class Failure extends RuntimeException {
        private final HttpStatus status;
        private final JsonNode body;

        Failure(HttpStatus status, JsonNode body) {
            super(body.path("error").asText());
            this.status = status;
            this.body = body;
        }

        public HttpStatus status() {
            return status;
        }

        public JsonNode body() {
            return body;
        }
    }

    private static final class FreshSnapshotRequired extends RuntimeException {}

    private record CaseScope(long warehouse, List<Long> products, LocalDate targetDate) {}
}
