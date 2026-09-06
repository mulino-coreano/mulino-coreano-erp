package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.interfacepackage.DispatcherRepository.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves authoritative event content within the caller's transaction, including its locks. */
final class EventPreparation {

    private final DispatcherRepository repository;

    EventPreparation(DispatcherRepository repository) {
        this.repository = repository;
    }

    PreparedEvent prepare(CreateEventRequest request) {
        String eventType = normalizeEventType(request.eventType());

        String externalRef = normalizeExternalRef(request.externalRef());
        if (externalRef == null) {
            throw new InvalidInterfaceRequestException(
                    "externalRef is required for event ingestion");
        }
        EventScope scope = resolveScope(request.caseRef(), request.workItemRef());
        Map<String, Object> sourcePayload = mutablePayload(request.payload());
        EventActor actor = EventActor.none();
        boolean globallyScopedGovernanceApproval = false;
        if ("WORK_ITEM_STATUS_CHANGED".equals(eventType)) {
            scope = resolveDependencySource(scope, sourcePayload);
        } else if ("CHANGE_REQUEST_APPROVED".equals(eventType)) {
            PreparedApproval approval = resolveApproval(scope, sourcePayload);
            scope = approval.scope();
            actor = approval.actor();
            globallyScopedGovernanceApproval = approval.globallyScopedGovernance();
        }
        if (globallyScopedGovernanceApproval && containsClaimEvidenceSelector(sourcePayload)) {
            throw new InvalidInterfaceRequestException(
                    "Claim/Evidence cannot narrow a globally scoped governance approval");
        }
        PreparedClaimEvidence claimEvidence = prepareClaimEvidence(scope, sourcePayload);
        scope = claimEvidence.scope();
        Map<String, Object> payload = enrichedPayload(sourcePayload, scope);
        return new PreparedEvent(eventType, externalRef, scope, actor, payload, claimEvidence);
    }

    private EventScope resolveScope(String caseRef, String workItemRef) {
        String requestedCaseRef = normalizeScopeRef(caseRef);
        String requestedWorkItemRef = normalizeScopeRef(workItemRef);
        Long caseId = null;
        String resolvedCaseRef = null;
        if (requestedCaseRef != null) {
            CaseScope resolvedCase =
                    repository
                            .caseScope(requestedCaseRef)
                            .orElseThrow(
                                    () ->
                                            new InvalidInterfaceRequestException(
                                                    "Unknown caseRef: " + requestedCaseRef));
            caseId = resolvedCase.caseId();
            resolvedCaseRef = resolvedCase.caseRef();
        }

        Long workItemId = null;
        String resolvedWorkItemRef = null;
        if (requestedWorkItemRef != null) {
            WorkItemScope workItem =
                    repository
                            .workItemScope(requestedWorkItemRef)
                            .orElseThrow(
                                    () ->
                                            new InvalidInterfaceRequestException(
                                                    "Unknown workItemRef: "
                                                            + requestedWorkItemRef));
            if (caseId != null && !caseId.equals(workItem.caseId())) {
                throw new InvalidInterfaceRequestException(
                        "workItemRef does not belong to caseRef");
            }
            workItemId = workItem.workItemId();
            resolvedWorkItemRef = workItem.workItemRef();
            caseId = workItem.caseId();
            resolvedCaseRef = workItem.caseRef();
        }
        return new EventScope(caseId, resolvedCaseRef, workItemId, resolvedWorkItemRef);
    }

    private EventScope resolveDependencySource(
            EventScope requestedScope, Map<String, Object> payload) {
        String suppliedReference =
                normalizedPayloadReference(
                        payload,
                        "workItemRef",
                        "work_item_ref",
                        "dependentWiRef",
                        "dependent_wi_ref");
        String dependencyRef = requestedScope.workItemRef();
        if (dependencyRef == null) {
            if (suppliedReference == null) {
                throw new InvalidInterfaceRequestException(
                        "WORK_ITEM_STATUS_CHANGED requires a real workItemRef");
            }
            dependencyRef = suppliedReference;
        } else if (suppliedReference != null && !dependencyRef.equals(suppliedReference)) {
            throw new InvalidInterfaceRequestException(
                    "payload workItemRef does not match resolved workItemRef");
        }

        String resolvedDependencyRef = dependencyRef;
        DependencySource source =
                repository
                        .lockDependency(resolvedDependencyRef)
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "Unknown workItemRef: " + resolvedDependencyRef));

        if (!"DONE".equals(source.status()) && !"CANCELLED".equals(source.status())) {
            throw new InvalidInterfaceRequestException(
                    "WORK_ITEM_STATUS_CHANGED source is not terminal: " + dependencyRef);
        }
        if (requestedScope.caseId() != null && !requestedScope.caseId().equals(source.caseId())) {
            throw new InvalidInterfaceRequestException("workItemRef does not belong to caseRef");
        }
        validateStatusAliases(payload, source.status());
        payload.put("status", source.status());
        payload.put("work_item_status", source.status());
        payload.put("workItemStatus", source.status());
        return new EventScope(
                source.caseId(), source.caseRef(), source.workItemId(), source.workItemRef());
    }

    private PreparedApproval resolveApproval(
            EventScope requestedScope, Map<String, Object> payload) {
        Long attentionId = identityValue(payload, "attention_request_id", "attentionRequestId");
        Long governanceActionId =
                identityValue(
                        payload,
                        "approval_id",
                        "approvalId",
                        "governance_action_id",
                        "governanceActionId");
        if ((attentionId == null) == (governanceActionId == null)) {
            throw new InvalidInterfaceRequestException(
                    "CHANGE_REQUEST_APPROVED requires exactly one authoritative approval identity");
        }

        if (attentionId != null) {
            AnsweredAttention attention =
                    repository
                            .answeredAttention(attentionId)
                            .orElseThrow(
                                    () ->
                                            new InvalidInterfaceRequestException(
                                                    "attentionRequestId is not an answered human"
                                                        + " approval: "
                                                            + attentionId));
            if (!isApprovedDecision(attention.answerText())) {
                throw new InvalidInterfaceRequestException(
                        "attentionRequestId does not contain an approved decision");
            }
            validateDecisionAliases(payload, "APPROVED");
            payload.put("attention_request_id", attentionId);
            payload.put("attentionRequestId", attentionId);
            payload.put("decision", "APPROVED");
            EventScope authoritative =
                    new EventScope(
                            attention.caseId(), attention.caseRef(),
                            attention.workItemId(), attention.workItemRef());
            assertCompatibleScope(requestedScope, authoritative);
            return new PreparedApproval(
                    authoritative, new EventActor("USER", attention.userId()), false);
        }

        if (repository.isPurchaseApproval(governanceActionId)) {
            return resolvePurchaseApproval(governanceActionId, requestedScope, payload);
        }

        ApprovedGovernanceAction approval =
                repository
                        .approvedGovernanceAction(governanceActionId)
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "approvalId is not backed by an approved governance"
                                                    + " decision: "
                                                        + governanceActionId));
        validateDecisionAliases(payload, "APPROVED");
        payload.put("approval_id", governanceActionId);
        payload.put("approvalId", governanceActionId);
        payload.put("governance_action_id", governanceActionId);
        payload.put("governanceActionId", governanceActionId);
        payload.put("decision", "APPROVED");
        EventScope authoritative = authoritativeGovernanceScope(approval, requestedScope);
        assertCompatibleScope(requestedScope, authoritative);
        return new PreparedApproval(
                authoritative,
                new EventActor("USER", approval.userId()),
                authoritative.caseId() == null);
    }

    private Optional<EventScope> governanceScope(ApprovedGovernanceAction approval) {
        if ("CASE".equalsIgnoreCase(approval.resourceType())) {
            return repository.caseScope(approval.resourceId());
        }
        if ("WORK_ITEM".equalsIgnoreCase(approval.resourceType())) {
            return repository.workItemScope(approval.resourceId());
        }
        return Optional.empty();
    }

    private PreparedApproval resolvePurchaseApproval(
            long id, EventScope requested, Map<String, Object> payload) {
        PreparedApproval approval =
                repository
                        .purchaseApproval(id)
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "Purchase approval requires its final active"
                                                    + " MANAGER decision"));
        assertCompatibleScope(requested, approval.scope());
        validateDecisionAliases(payload, "APPROVED");
        for (String alias :
                List.of("approval_id", "approvalId", "governance_action_id", "governanceActionId"))
            payload.put(alias, Long.toString(id));
        payload.put("decision", "APPROVED");
        return approval;
    }

    private EventScope authoritativeGovernanceScope(
            ApprovedGovernanceAction approval, EventScope requestedScope) {
        Optional<EventScope> resolved = governanceScope(approval);
        if ("CASE".equalsIgnoreCase(approval.resourceType())
                || "WORK_ITEM".equalsIgnoreCase(approval.resourceType())) {
            return resolved.orElseThrow(
                    () ->
                            new InvalidInterfaceRequestException(
                                    "Approved governance authoritative resource does not exist"));
        }
        if (requestedScope.caseId() != null || requestedScope.workItemId() != null) {
            throw new InvalidInterfaceRequestException(
                    "Approval scope cannot be supplied for an unmapped governance resource");
        }
        return new EventScope(null, null, null, null);
    }

    private PreparedClaimEvidence prepareClaimEvidence(
            EventScope requestedScope, Map<String, Object> payload) {
        Long claimId = identityValue(payload, "claim_id", "claimId");
        String evidenceRef = normalizedPayloadReference(payload, "evidence_ref", "evidenceRef");
        if (claimId == null && evidenceRef == null) {
            return new PreparedClaimEvidence(requestedScope, null, null, null);
        }
        if (claimId == null || evidenceRef == null) {
            throw new InvalidInterfaceRequestException(
                    "claimId and evidenceRef must be supplied together");
        }

        ClaimEvidenceTarget target =
                repository
                        .claimEvidenceTarget(claimId, evidenceRef)
                        .orElseThrow(
                                () ->
                                        new InvalidInterfaceRequestException(
                                                "Unknown claimId or evidenceRef"));
        if (target.evidenceCaseId() == null || target.claimCaseId() != target.evidenceCaseId()) {
            throw new InvalidInterfaceRequestException(
                    "Claim and Evidence must belong to the same Case");
        }
        if (requestedScope.caseId() != null && requestedScope.caseId() != target.claimCaseId()) {
            throw new InvalidInterfaceRequestException(
                    "Event, Claim, and Evidence must belong to the same Case");
        }

        String relation = normalizeClaimEvidenceRelation(payload.get("relation"));
        payload.put("claimId", claimId);
        payload.put("claim_id", claimId);
        payload.put("evidenceRef", evidenceRef);
        payload.put("evidence_ref", evidenceRef);
        payload.put("relation", relation);
        EventScope authoritativeScope =
                requestedScope.caseId() == null
                        ? new EventScope(
                                target.claimCaseId(), target.caseRef(),
                                requestedScope.workItemId(), requestedScope.workItemRef())
                        : requestedScope;
        return new PreparedClaimEvidence(
                authoritativeScope, claimId, target.evidenceId(), relation);
    }

    private boolean containsClaimEvidenceSelector(Map<String, Object> payload) {
        return payload.containsKey("claim_id")
                || payload.containsKey("claimId")
                || payload.containsKey("evidence_ref")
                || payload.containsKey("evidenceRef");
    }

    private String normalizedPayloadReference(Map<String, Object> payload, String... aliases) {
        String result = null;
        for (String alias : aliases) {
            Object value = payload.get(alias);
            if (value == null) {
                continue;
            }
            String normalized = normalizeScopeRef(value.toString());
            if (normalized == null) {
                throw new InvalidInterfaceRequestException(alias + " must not be blank");
            }
            if (result != null && !result.equals(normalized)) {
                throw new InvalidInterfaceRequestException("Conflicting payload reference aliases");
            }
            result = normalized;
        }
        return result;
    }

    private String normalizeClaimEvidenceRelation(Object supplied) {
        String relation = supplied == null ? "SUPPORTS" : supplied.toString().trim();
        if (!"SUPPORTS".equals(relation) && !"REFUTES".equals(relation)) {
            throw new InvalidInterfaceRequestException("relation must be SUPPORTS or REFUTES");
        }
        return relation;
    }

    private void assertCompatibleScope(EventScope requested, EventScope authoritative) {
        if (requested.caseId() != null && !requested.caseId().equals(authoritative.caseId())) {
            throw new InvalidInterfaceRequestException("Approval scope does not match caseRef");
        }
        if (!Objects.equals(requested.workItemId(), authoritative.workItemId())
                && requested.workItemId() != null) {
            throw new InvalidInterfaceRequestException("Approval scope does not match workItemRef");
        }
    }

    private Long identityValue(Map<String, Object> payload, String... aliases) {
        Long identity = null;
        for (String alias : aliases) {
            Object supplied = payload.get(alias);
            if (supplied == null) {
                continue;
            }
            Long parsed = positiveLong(supplied, alias);
            if (identity != null && !identity.equals(parsed)) {
                throw new InvalidInterfaceRequestException("Conflicting approval identity aliases");
            }
            identity = parsed;
        }
        return identity;
    }

    private Long positiveLong(Object value, String fieldName) {
        try {
            long parsed = new BigDecimal(value.toString()).longValueExact();
            if (parsed <= 0) {
                throw new ArithmeticException("non-positive");
            }
            return parsed;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new InvalidInterfaceRequestException(
                    fieldName + " must be a positive integer", failure);
        }
    }

    private void validateDecisionAliases(Map<String, Object> payload, String decision) {
        for (String alias : List.of("decision", "expected_decision", "expectedDecision")) {
            Object supplied = payload.get(alias);
            if (supplied != null && !decision.equals(supplied.toString())) {
                throw new InvalidInterfaceRequestException(
                        "payload " + alias + " does not match the authoritative decision");
            }
        }
    }

    private boolean isApprovedDecision(String answer) {
        return answer != null
                && ("APPROVED".equalsIgnoreCase(answer.trim())
                        || "APPROVE".equalsIgnoreCase(answer.trim()));
    }

    private void validateStatusAliases(Map<String, Object> payload, String resolvedStatus) {
        for (String alias : List.of("status", "work_item_status", "workItemStatus")) {
            Object supplied = payload.get(alias);
            if (supplied != null && !resolvedStatus.equals(supplied.toString())) {
                throw new InvalidInterfaceRequestException(
                        "payload " + alias + " does not match current Work Item status");
            }
        }
    }

    private Map<String, Object> mutablePayload(Map<String, Object> original) {
        return original == null ? new LinkedHashMap<>() : new LinkedHashMap<>(original);
    }

    private Map<String, Object> enrichedPayload(Map<String, Object> original, EventScope scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (original != null) {
            payload.putAll(original);
        }
        validatePayloadIdentity(payload, scope.caseId(), "caseId", "case_id");
        validatePayloadIdentity(payload, scope.caseRef(), "caseRef", "case_ref");
        validatePayloadIdentity(payload, scope.workItemId(), "workItemId", "work_item_id");
        validatePayloadIdentity(payload, scope.workItemRef(), "workItemRef", "work_item_ref");
        putCanonical(payload, "caseId", scope.caseId());
        putCanonical(payload, "case_id", scope.caseId());
        putCanonical(payload, "workItemId", scope.workItemId());
        putCanonical(payload, "work_item_id", scope.workItemId());
        putCanonical(payload, "caseRef", scope.caseRef());
        putCanonical(payload, "case_ref", scope.caseRef());
        putCanonical(payload, "workItemRef", scope.workItemRef());
        putCanonical(payload, "work_item_ref", scope.workItemRef());
        return Collections.unmodifiableMap(payload);
    }

    private void validatePayloadIdentity(
            Map<String, Object> payload, Object resolved, String... aliases) {
        if (resolved == null) {
            return;
        }
        for (String alias : aliases) {
            Object supplied = payload.get(alias);
            if (supplied != null && !equivalentIdentity(supplied, resolved)) {
                throw new InvalidInterfaceRequestException(
                        "payload " + alias + " does not match resolved " + aliases[0]);
            }
        }
    }

    private boolean equivalentIdentity(Object supplied, Object resolved) {
        if (supplied instanceof Number left && resolved instanceof Number right) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))
                        == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Objects.equals(supplied, resolved);
    }

    private void putCanonical(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String normalizeExternalRef(String externalRef) {
        return externalRef == null || externalRef.isBlank() ? null : externalRef.trim();
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new InvalidInterfaceRequestException("eventType is required");
        }
        String normalized = eventType.trim();
        if (normalized.length() > 100) {
            throw new InvalidInterfaceRequestException("eventType must be at most 100 characters");
        }
        return normalized;
    }

    private String normalizeScopeRef(String reference) {
        return reference == null || reference.isBlank() ? null : reference.trim();
    }

    record PreparedEvent(
            String eventType,
            String externalRef,
            EventScope scope,
            EventActor actor,
            Map<String, Object> payload,
            PreparedClaimEvidence claimEvidence) {}
}
