package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.*;

import com.mulinocoreano.backend.generated.enums.*;
import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.security.*;

import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AttentionAnswerService {
    private final AttentionAnswerTransactions transactions;
    private final AttentionAnswerRepository repository;
    private final RequestIdempotency idempotency;
    private final RunService runs;
    private final ObjectMapper mapper;

    public AttentionAnswerService(
            AttentionAnswerTransactions transactions,
            AttentionAnswerRepository repository,
            RequestIdempotency idempotency,
            RunService runs,
            ObjectMapper mapper) {
        this.transactions = transactions;
        this.repository = repository;
        this.idempotency = idempotency;
        this.runs = runs;
        this.mapper = mapper;
    }

    public JsonNode answer(long id, AttentionAnswerRequest request, ErpActor actor, String key) {
        if (!(actor instanceof HumanActor human) || !human.capabilities().contains("work:write"))
            throw new AccessDeniedException("Human work:write capability is required");
        if (request == null
                || request.answer() == null
                || request.answer().isBlank()
                || request.answer().length() > 8000
                || request.expectedVersion() == null
                || request.expectedVersion() < 1
                || request.scope() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Attention answer");
        return transactions.execute(
                () -> {
                    String namespace = "attention.answer:" + human.userId();
                    idempotency.coordinate(namespace, key);
                    if (!repository.lockHuman(human.userId()))
                        throw new AccessDeniedException(
                                "An active OPERATOR or MANAGER is required");
                    return idempotency.execute(
                            namespace,
                            key,
                            Map.of("attentionRequestId", id, "request", request),
                            () -> apply(id, request, human.userId()));
                });
    }

    private Object apply(long id, AttentionAnswerRequest request, long user) {
        var initial = repository.attention(id, false);
        var work = repository.lockWork(initial.get(ATTENTION_REQUESTS.WORK_ITEM_ID));
        var caseRow = repository.lockCase(initial.get(ATTENTION_REQUESTS.CASE_ID));
        var a = repository.attention(id, true);
        if (!Objects.equals(
                        initial.get(ATTENTION_REQUESTS.CASE_ID), a.get(ATTENTION_REQUESTS.CASE_ID))
                || !Objects.equals(
                        initial.get(ATTENTION_REQUESTS.WORK_ITEM_ID),
                        a.get(ATTENTION_REQUESTS.WORK_ITEM_ID)))
            throw conflict("Attention target changed");
        if (a.get(ATTENTION_REQUESTS.GOVERNANCE_ACTION_ID) != null
                || a.get(ATTENTION_REQUESTS.REASON_TYPE) == AttentionReasonType.AUTHORITY_REQUIRED)
            throw conflict("Use the approval decision endpoint for authority requests");
        if (a.get(ATTENTION_REQUESTS.STATUS) != AttentionRequestStatus.OPEN
                || !a.get(ATTENTION_REQUESTS.VERSION).equals(request.expectedVersion()))
            throw conflict("Attention was answered or its version changed");
        if (caseRow.get(CASES.STATUS) == CaseStatus.CLOSED
                || caseRow.get(CASES.STATUS) == CaseStatus.RESOLVED)
            throw conflict("Case is no longer active");
        var metadata =
                Map.of(
                        "sourceAttentionId",
                        id,
                        "sourceAttentionVersion",
                        a.get(ATTENTION_REQUESTS.VERSION),
                        "kind",
                        "HUMAN_CONTEXT_ANSWER");
        long decision = repository.decision(a, request, user, json(metadata));
        var resolved = repository.resolve(id, request, user, LocalDateTime.now(ZoneOffset.UTC));
        repository.participant(a.get(ATTENTION_REQUESTS.CASE_ID), user);
        // Record a fact directly: general answers never enter approval event matching.
        long event =
                repository.event(
                        a,
                        user,
                        json(
                                Map.of(
                                        "attentionRequestId",
                                        id,
                                        "decisionId",
                                        decision,
                                        "scope",
                                        request.scope().name(),
                                        "version",
                                        resolved.get(ATTENTION_REQUESTS.VERSION))));
        var result = new LinkedHashMap<String, Object>();
        result.put("attentionRequestId", id);
        result.put("caseRef", caseRow.get(CASES.CASE_REF));
        result.put("workItemRef", work == null ? null : work.get(WORK_ITEMS.WORK_ITEM_REF));
        result.put("status", "ANSWERED");
        result.put("version", resolved.get(ATTENTION_REQUESTS.VERSION));
        result.put("answer", request.answer());
        result.put("scope", request.scope().name());
        result.put("decisionId", decision);
        result.put("resolvedByUserId", user);
        result.put(
                "resolvedAt",
                resolved.get(ATTENTION_REQUESTS.RESOLVED_AT).toInstant(ZoneOffset.UTC));
        result.put("resume", resume(a, work, caseRow.get(CASES.CASE_REF), event));
        return result;
    }

    private Object resume(Record a, Record work, String caseRef, long event) {
        if (work == null) return Map.of("status", "NO_WORK_ITEM");
        if (repository.isManagedWork(work.get(WORK_ITEMS.WORK_ITEM_ID)))
            return Map.of("status", "SERVER_MANAGED");
        if (work.get(WORK_ITEMS.STATUS) != WorkItemStatus.BLOCKED)
            return Map.of("status", work.get(WORK_ITEMS.STATUS).getLiteral());
        if (repository.hasOpenAttention(
                a.get(ATTENTION_REQUESTS.CASE_ID), work.get(WORK_ITEMS.WORK_ITEM_ID)))
            return Map.of("status", "BLOCKED_ATTENTION");
        if (repository.hasWait(work.get(WORK_ITEMS.WORK_ITEM_ID)))
            return Map.of("status", "BLOCKED_WAITING");
        if (runs.hasActiveRun(work.get(WORK_ITEMS.WORK_ITEM_ID)))
            return Map.of("status", "LIVE_RUN");
        if (work.get(WORK_ITEMS.ASSIGNED_AGENT_ID) == null
                || work.get(WORK_ITEMS.ASSIGNED_USER_ID) != null)
            return Map.of("status", "BLOCKED");
        var agent = repository.lockAgent(work.get(WORK_ITEMS.ASSIGNED_AGENT_ID));
        if (!agent.get(AGENTS.IS_ACTIVE)) return Map.of("status", "BLOCKED_INACTIVE_AGENT");
        repository.ready(work.get(WORK_ITEMS.WORK_ITEM_ID));
        var run =
                runs.createRun(
                        new CreateRunRequest(
                                agent.get(AGENTS.AGENT_KEY),
                                caseRef,
                                work.get(WORK_ITEMS.WORK_ITEM_REF),
                                runs.defaultRuntime()),
                        event);
        if (!"QUEUED".equals(run.status())) throw conflict("Unable to queue resumed Run");
        return Map.of("status", "QUEUED", "runRef", run.runRef());
    }

    private JSONB json(Object value) {
        return JSONB.valueOf(mapper.writeValueAsString(value));
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
