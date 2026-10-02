package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.interfacepackage.CaseIntakeRepository.ProductTarget;
import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.security.HumanActor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Accepts a human objective and atomically creates its initial responsibility and queued Run. */
@Service
public class CaseIntakeService {
    private static final Set<String> SUPPORTED_CHANNELS =
            Set.of("CHAT", "SLACK", "EMAIL", "DASHBOARD", "API");
    private final CaseIntakeRepository repository;
    private final RunService runs;
    private final RequestIdempotency idempotency;
    private final CanonicalJson json;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final InterfaceQueries queries;

    public CaseIntakeService(
            CaseIntakeRepository repository,
            RunService runs,
            RequestIdempotency idempotency,
            CanonicalJson json,
            ObjectMapper mapper,
            @Qualifier("planningClock") Clock clock,
            InterfaceQueries queries) {
        this.repository = repository;
        this.runs = runs;
        this.idempotency = idempotency;
        this.json = json;
        this.mapper = mapper;
        this.clock = clock;
        this.queries = queries;
    }

    @Transactional
    public CaseDto createCase(CreateCaseRequest request) {
        return createCase(request, UUID.randomUUID().toString());
    }

    @Transactional
    public CaseDto createCase(CreateCaseRequest input, String key) {
        var request = validate(input);
        var actor = requireDelegatingHuman();
        JsonNode receipt =
                idempotency.execute(
                        "case.create:" + actor.userId(),
                        key,
                        request,
                        () -> createOrReuse(request, actor.userId()));
        return mapper.treeToValue(receipt, CaseDto.class);
    }

    private HumanActor requireDelegatingHuman() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof HumanActor actor)
                || !actor.capabilities().contains("work:write")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "A human work delegation is required");
        }
        if (!repository.lockDelegatingUser(actor.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "The delegating user is inactive or not permitted");
        }
        return actor;
    }

    private CaseDto createOrReuse(ValidatedCaseRequest request, long userId) {
        Map<String, Object> target = resolveReplenishment(request.replenishment());
        if (target != null) {
            long warehouse = (Long) target.get("warehouseId");
            idempotency.coordinate("planning.warehouse", Long.toString(warehouse));
            repository.lockWarehouse(warehouse);
            var existing = repository.activePlanningCase(warehouse);
            if (existing.isPresent())
                return reuse(queries.getCase(existing.get()), request, target, userId);
        }

        long agentId =
                repository
                        .activeOrchestrator()
                        .orElseThrow(
                                () -> unavailable("No active ORCHESTRATOR agent is configured"));
        long channelId =
                repository
                        .defaultChannel(request.channel())
                        .orElseThrow(
                                () ->
                                        unavailable(
                                                "No default channel is configured for "
                                                        + request.channel()));
        String caseRef = newPublicRef("CASE");
        long caseId =
                repository.insertCase(
                        caseRef,
                        truncate(request.objective(), 60),
                        request.objective(),
                        channelId,
                        userId,
                        target == null ? Map.of() : Map.of("replenishment", target));
        repository.addHumanParticipant(caseId, userId);
        if (target != null) repository.bindPlanningCase(caseId, (Long) target.get("warehouseId"));
        repository.addAgentParticipant(caseId, agentId);

        String workRef = newPublicRef("WI");
        repository.insertInitialWork(workRef, caseId, agentId);
        RunDto queued =
                runs.createRun(
                        new CreateRunRequest("ORCHESTRATOR", caseRef, workRef, "CODEX"), null);
        if (!"QUEUED".equals(queued.status()))
            throw unavailable("Initial execution context could not be queued");
        return queries.getCase(caseRef);
    }

    private CaseDto reuse(
            CaseDto prior, ValidatedCaseRequest request, Map<String, Object> target, long userId) {
        JsonNode previous = prior.metadata() == null ? null : prior.metadata().get("replenishment");
        if (!Set.of("OPEN", "IN_PROGRESS", "WAITING").contains(prior.status())
                || !prior.objective().equals(request.objective())
                || !sameScope(previous, target)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Review the existing planning Case: " + prior.caseRef());
        }
        repository.addHumanParticipant(prior.caseId(), userId);
        return prior.reusedReceipt();
    }

    private boolean sameScope(JsonNode previous, Map<String, Object> target) {
        if (previous == null || !previous.isObject()) return false;
        for (String field : List.of("warehouseId", "productIds", "targetDate")) {
            if (!previous.hasNonNull(field)
                    || !json.write(previous.get(field)).equals(json.write(target.get(field))))
                return false;
        }
        return true;
    }

    private Map<String, Object> resolveReplenishment(CreateCaseRequest.Replenishment target) {
        if (target == null) return null;
        var policies = repository.policies(target.warehouseId());
        if (policies.size() != 1)
            throw new InvalidInterfaceRequestException(
                    "Select one warehouse with a planning policy");
        var policy = policies.getFirst();
        var products = repository.products(target.productSkus());
        if (products.size() != target.productSkus().size()) {
            throw new InvalidInterfaceRequestException(
                    "Every SKU must identify one active finished product");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate end =
                target.targetDate() == null
                        ? today.plusDays(policy.horizon() - 1L)
                        : target.targetDate();
        long horizon = ChronoUnit.DAYS.between(today, end) + 1;
        if (horizon < 1 || horizon > 90)
            throw new InvalidInterfaceRequestException(
                    "Target date must be within the next 1..90 days");
        return Map.of(
                "warehouseId",
                policy.warehouseId(),
                "productIds",
                products.stream().map(ProductTarget::id).toList(),
                "productSkus",
                products.stream().map(ProductTarget::sku).toList(),
                "targetDate",
                end.toString());
    }

    private ValidatedCaseRequest validate(CreateCaseRequest input) {
        if (input == null || input.objective() == null || input.objective().isBlank()) {
            throw new InvalidInterfaceRequestException("objective is required");
        }
        if (input.intentType() != null && !"ACT".equals(input.intentType())) {
            throw new InvalidInterfaceRequestException("intentType must be ACT when supplied");
        }
        String channel = input.channel() == null ? "CHAT" : input.channel();
        if (!SUPPORTED_CHANNELS.contains(channel))
            throw new InvalidInterfaceRequestException("channel is invalid");
        var target = input.replenishment();
        if (target != null) {
            if (target.productSkus() == null
                    || target.productSkus().isEmpty()
                    || target.productSkus().size() > 100
                    || target.productSkus().stream()
                            .anyMatch(s -> s == null || s.isBlank() || s.length() > 50)
                    || (target.warehouseId() != null && target.warehouseId() <= 0)) {
                throw new InvalidInterfaceRequestException("Invalid replenishment scope");
            }
            target =
                    new CreateCaseRequest.Replenishment(
                            target.productSkus().stream()
                                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                                    .distinct()
                                    .sorted()
                                    .toList(),
                            target.warehouseId(),
                            target.targetDate());
        }
        return new ValidatedCaseRequest(input.objective().trim(), channel, target);
    }

    private String newPublicRef(String prefix) {
        return prefix
                + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 18 - prefix.length());
    }

    private String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    private record ValidatedCaseRequest(
            String objective, String channel, CreateCaseRequest.Replenishment replenishment) {}
}
