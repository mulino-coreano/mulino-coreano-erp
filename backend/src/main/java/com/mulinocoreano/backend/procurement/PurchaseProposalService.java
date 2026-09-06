package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.execution.RunCapabilityAccess;
import com.mulinocoreano.backend.execution.RunCapabilityAccess.RunScope;
import com.mulinocoreano.backend.execution.RunExecutionService;
import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.security.AgentActor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Prepares an immutable purchasing proposal and yields the Run to its human approver. */
@Service
public class PurchaseProposalService {
    private final PurchaseTransactions transactions;
    private final PurchasePlanning planning;
    private final PurchaseRepository repository;
    private final RequestIdempotency idempotency;
    private final RunCapabilityAccess capabilities;
    private final RunExecutionService execution;
    private final CanonicalJson json;
    private final ObjectMapper mapper;

    public PurchaseProposalService(
            PurchaseTransactions transactions,
            PurchasePlanning planning,
            PurchaseRepository repository,
            RequestIdempotency idempotency,
            RunCapabilityAccess capabilities,
            RunExecutionService execution,
            CanonicalJson json,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.planning = planning;
        this.repository = repository;
        this.idempotency = idempotency;
        this.capabilities = capabilities;
        this.execution = execution;
        this.json = json;
        this.mapper = mapper;
    }

    public JsonNode propose(String planRef, String token, AgentActor actor, String key) {
        return transactions.execute(
                () -> {
                    String namespace = "purchase.propose:" + actor.workItemRef();
                    idempotency.coordinate(namespace, key);
                    planning.lockSources();

                    var plan = planning.load(planRef);
                    idempotency.coordinate("planning.warehouse", Long.toString(plan.warehouse()));
                    var scope = capabilities.requireLocked(token, "PROCUREMENT", actor.caseRef());
                    if (scope.caseId() != plan.caseId()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Plan is not available in this Case");
                    }
                    repository.lockPlan(plan.id());

                    return idempotency.execute(
                            namespace,
                            key,
                            Map.of("planRef", planRef),
                            () -> prepare(planning.load(planRef), scope, token));
                });
    }

    private Map<String, Object> prepare(PurchasePlan plan, RunScope scope, String token) {
        PurchaseBundle bundle;
        try {
            bundle = planning.currentBundle(plan);
        } catch (IllegalArgumentException changed) {
            repository.attention(
                    plan.caseId(), scope.workItemId(), "계획 재계산 필요", "현재 자료와 계획을 다시 확인해 주세요.", null);
            capabilities.requireLocked(token, "PROCUREMENT", scope.caseRef());
            return Map.of("error", "PLAN_REQUIRES_RECALCULATION", "planRef", plan.ref());
        }

        if ("NO_PURCHASE_REQUIRED".equals(bundle.status())) {
            return finishWithoutPurchase(plan, scope, token);
        }
        if (plan.requester() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A purchase request needs its original human requester");
        }
        if (repository.proposalExists(plan.id())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This plan already has a purchase proposal");
        }
        return requestApproval(plan, scope, bundle);
    }

    private Map<String, Object> finishWithoutPurchase(
            PurchasePlan plan, RunScope scope, String token) {
        repository.setOutcome(scope.workItemId(), plan.id(), "NO_PURCHASE_REQUIRED");
        capabilities.requireLocked(token, "PROCUREMENT", scope.caseRef());
        execution.finishAuthorized(scope.runId(), "DONE", "현재 계획에는 원재료 구매가 필요하지 않습니다.", List.of());

        return Map.of(
                "status",
                "NO_PURCHASE_REQUIRED",
                "planRef",
                plan.ref(),
                "executionResult",
                Map.of(
                        "outcome",
                        "DONE",
                        "summary",
                        "추가 원재료 구매가 필요하지 않습니다.",
                        "waitingConditions",
                        List.of(),
                        "resultRef",
                        plan.ref()));
    }

    private Map<String, Object> requestApproval(
            PurchasePlan plan, RunScope scope, PurchaseBundle bundle) {
        String hash = json.sha256(bundle);
        long approvalId = repository.insertProposal(plan, scope, bundle, hash);
        repository.setOutcome(scope.workItemId(), plan.id(), "PROPOSED");
        repository.attention(
                plan.caseId(),
                scope.workItemId(),
                "원재료 구매 승인 필요",
                "APPROVAL-"
                        + approvalId
                        + ": "
                        + bundle.totalKrw().toPlainString()
                        + "원 발주안의 수량·공급처·납기를 검토해 주세요.",
                approvalId);

        // The requester owns the goal; the linked agent performed this proposal.
        repository.audit(
                approvalId,
                null,
                "PURCHASE_PROPOSED",
                "REPLENISHMENT_PLAN",
                plan.id(),
                mapper.valueToTree(bundle));
        execution.awaitPurchaseApproval(scope.runId(), approvalId, "구매안이 저장되어 MANAGER 결정을 기다립니다.");

        return Map.of(
                "status",
                "PENDING_APPROVAL",
                "approvalId",
                approvalId,
                "version",
                plan.version(),
                "proposalHash",
                hash,
                "planRef",
                plan.ref(),
                "executionResult",
                Map.of(
                        "outcome",
                        "WAITING",
                        "summary",
                        "MANAGER의 구매 결정을 기다립니다.",
                        "waitingConditions",
                        List.of(),
                        "resultRef",
                        "APPROVAL-" + approvalId));
    }
}
