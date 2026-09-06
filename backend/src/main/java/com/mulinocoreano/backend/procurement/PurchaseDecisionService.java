package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.interfacepackage.CreateEventRequest;
import com.mulinocoreano.backend.interfacepackage.DispatcherService;
import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.security.ErpActor;
import com.mulinocoreano.backend.security.HumanActor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** Owns the atomic boundary between a human decision, ERP writes and the resume event. */
@Service
public class PurchaseDecisionService {
    private final PurchaseTransactions transactions;
    private final PurchasePlanning planning;
    private final PurchaseRepository repository;
    private final RequestIdempotency idempotency;
    private final PurchaseVerificationService verification;
    private final PurchaseQueries queries;
    private final DispatcherService dispatcher;
    private final CanonicalJson json;
    private final ObjectMapper mapper;

    public PurchaseDecisionService(
            PurchaseTransactions transactions,
            PurchasePlanning planning,
            PurchaseRepository repository,
            RequestIdempotency idempotency,
            PurchaseVerificationService verification,
            PurchaseQueries queries,
            DispatcherService dispatcher,
            CanonicalJson json,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.planning = planning;
        this.repository = repository;
        this.idempotency = idempotency;
        this.verification = verification;
        this.queries = queries;
        this.dispatcher = dispatcher;
        this.json = json;
        this.mapper = mapper;
    }

    public JsonNode decide(
            long approvalId, PurchaseDecisionRequest request, ErpActor principal, String key) {
        HumanActor human = requireHumanDecisionScope(principal);
        return transactions.execute(
                () -> {
                    String namespace = "purchase.decide:" + human.userId();
                    idempotency.coordinate(namespace, key);
                    planning.lockSources();

                    var initial = repository.action(approvalId, false);
                    var plan = planning.load(initial.planRef());
                    idempotency.coordinate("planning.warehouse", Long.toString(plan.warehouse()));
                    if (!repository.lockActiveManager(human.userId())) {
                        throw new AccessDeniedException("An active MANAGER is required");
                    }
                    repository.lockWorkAndCase(initial);
                    repository.lockPlan(plan.id());
                    var approval = repository.action(approvalId, true);

                    return idempotency.execute(
                            namespace,
                            key,
                            Map.of("approvalId", approvalId, "request", request),
                            () -> applyDecision(approval, request, human.userId()));
                });
    }

    private Object applyDecision(
            PurchaseApproval approval, PurchaseDecisionRequest request, long userId) {
        if (!approval.matches(request)) {
            return Map.of("error", "PROPOSAL_VERSION_MISMATCH");
        }
        if (!"PENDING".equals(approval.status())) {
            if ("APPROVED".equals(approval.status()) && "APPROVE".equals(request.decision())) {
                return queries.approval(approval.id());
            }
            return Map.of("error", "ALREADY_DECIDED");
        }
        if ("BLOCK".equals(request.decision())) {
            closeProposal(approval, "BLOCKED", userId, request.reason());
            recordDecision(approval, userId, request);
            return queries.approval(approval.id());
        }
        return approve(approval, userId, request);
    }

    private Object approve(
            PurchaseApproval approval, long userId, PurchaseDecisionRequest request) {
        PurchaseBundle bundle;
        String caseRef;
        try {
            var plan = planning.load(approval.planRef());
            caseRef = plan.caseRef();
            bundle = planning.currentBundle(plan);
            if (!json.sha256(bundle).equals(approval.hash())) {
                throw new IllegalArgumentException("PROPOSAL_CHANGED");
            }
        } catch (IllegalArgumentException stale) {
            // Return a committed failure receipt: throwing here would roll EXPIRED back.
            closeProposal(approval, "EXPIRED", userId, "현재 입력이 변경되어 재계산과 새 승인이 필요합니다.");
            return Map.of("error", "PROPOSAL_EXPIRED", "approvalId", approval.id());
        }

        long applicationId = repository.nextApplicationId();
        repository.writeOrders(applicationId, userId, bundle);
        JsonNode manifest = verification.manifest(applicationId);
        if (!verification.matches(bundle, userId, manifest)) {
            throw new IllegalStateException("Purchase application verification failed");
        }

        repository.updateApprovalStatus(approval.id(), "APPROVED");
        long decisionId = recordDecision(approval, userId, request);
        repository.insertApplication(applicationId, approval, decisionId, userId, manifest);
        repository.setOutcome(approval.workId(), approval.planId(), "APPLIED");
        repository.resolveAttention(approval.id(), userId, "승인: " + request.reason(), "ANSWERED");
        repository.audit(
                approval.id(),
                userId,
                "PURCHASE_APPLIED",
                "PURCHASE_APPLICATION",
                applicationId,
                manifest);
        dispatcher.ingest(
                new CreateEventRequest(
                        "CHANGE_REQUEST_APPROVED",
                        "purchase-approved-" + approval.id(),
                        caseRef,
                        null,
                        Map.of("approvalId", Long.toString(approval.id()))));
        return queries.approval(approval.id());
    }

    private long recordDecision(
            PurchaseApproval approval, long userId, PurchaseDecisionRequest request) {
        long id = repository.recordDecision(approval, userId, request);
        repository.audit(
                approval.id(),
                userId,
                "PURCHASE_DECIDED",
                "GOVERNANCE_ACTION",
                approval.id(),
                mapper.valueToTree(request));
        return id;
    }

    private void closeProposal(
            PurchaseApproval approval, String status, long userId, String reason) {
        repository.updateApprovalStatus(approval.id(), status);
        repository.cancelApprovalWork(approval.workId());
        repository.setOutcome(approval.workId(), approval.planId(), status);
        repository.resolveAttention(
                approval.id(), userId, reason, "EXPIRED".equals(status) ? "EXPIRED" : "ANSWERED");
        repository.attention(
                approval.caseId(),
                null,
                "재보충 방침 확인 필요",
                reason + " 자동으로 같은 발주안을 다시 요청하지 않습니다.",
                null);
        repository.audit(
                approval.id(),
                userId,
                "PURCHASE_" + status,
                "GOVERNANCE_ACTION",
                approval.id(),
                mapper.valueToTree(Map.of("reason", reason)));
    }

    private HumanActor requireHumanDecisionScope(ErpActor principal) {
        if (principal instanceof HumanActor human
                && human.capabilities().contains("procurement:decide")) {
            return human;
        }
        throw new AccessDeniedException("Human purchasing authority is required");
    }
}
