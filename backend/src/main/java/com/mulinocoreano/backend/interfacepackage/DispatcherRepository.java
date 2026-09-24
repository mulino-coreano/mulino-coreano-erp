package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import com.mulinocoreano.backend.generated.enums.*;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Dispatcher persistence; matching, scope validation and approval policy remain in the service. */
@Repository
public class DispatcherRepository {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public DispatcherRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    List<EventDto> listEvents(String caseRef) {
        var e = EVENTS.as("e");
        var c = CASES.as("c");
        var w = WORK_ITEMS.as("w");
        var wait = WAITING_CONDITIONS.as("resolved_wait");
        var item = WORK_ITEMS.as("affected_item");
        var affected = CASES.as("affected_case");
        var run = RUNS.as("triggered_run");
        var filter =
                caseRef == null
                        ? noCondition()
                        : c.CASE_REF
                                .eq(caseRef)
                                .or(
                                        exists(
                                                selectOne()
                                                        .from(wait)
                                                        .join(item)
                                                        .on(item.WORK_ITEM_ID.eq(wait.WORK_ITEM_ID))
                                                        .join(affected)
                                                        .on(affected.CASE_ID.eq(item.CASE_ID))
                                                        .where(
                                                                wait.RESOLVED_BY_EVENT_ID
                                                                        .eq(e.EVENT_ID)
                                                                        .and(
                                                                                affected.CASE_REF
                                                                                        .eq(
                                                                                                caseRef)))))
                                .or(
                                        exists(
                                                selectOne()
                                                        .from(run)
                                                        .join(affected)
                                                        .on(affected.CASE_ID.eq(run.CASE_ID))
                                                        .where(
                                                                run.TRIGGER_EVENT_ID
                                                                        .eq(e.EVENT_ID)
                                                                        .and(
                                                                                affected.CASE_REF
                                                                                        .eq(
                                                                                                caseRef)))));
        return dsl.select(
                        e.EVENT_ID,
                        e.EVENT_TYPE,
                        e.EXTERNAL_REF,
                        c.CASE_REF,
                        w.WORK_ITEM_REF,
                        e.PAYLOAD,
                        e.OCCURRED_AT)
                .from(e)
                .leftJoin(c)
                .on(c.CASE_ID.eq(e.CASE_ID))
                .leftJoin(w)
                .on(w.WORK_ITEM_ID.eq(e.WORK_ITEM_ID))
                .where(filter)
                .orderBy(e.OCCURRED_AT.desc(), e.EVENT_ID.desc())
                .fetch(
                        r ->
                                new EventDto(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5(),
                                        parsePayload(r.value6()),
                                        Timestamp.valueOf(r.value7()).toInstant()));
    }

    int satisfyWaiting(long waitingId, long eventId) {
        var w = WAITING_CONDITIONS;
        return dsl.update(w)
                .set(w.STATUS, WaitingStatus.SATISFIED)
                .set(w.RESOLVED_AT, currentLocalDateTime())
                .set(w.RESOLVED_BY_EVENT_ID, eventId)
                .where(
                        w.WAITING_CONDITION_ID
                                .eq(waitingId)
                                .and(w.STATUS.eq(WaitingStatus.ACTIVE))
                                .and(w.RESOLVED_BY_EVENT_ID.isNull()))
                .execute();
    }

    long activeConditions(long id) {
        return dsl.fetchCount(
                WAITING_CONDITIONS,
                WAITING_CONDITIONS
                        .WORK_ITEM_ID
                        .eq(id)
                        .and(WAITING_CONDITIONS.STATUS.eq(WaitingStatus.ACTIVE)));
    }

    int makeReady(long id) {
        return dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.READY)
                .setNull(WORK_ITEMS.RESOLVED_AT)
                .where(
                        WORK_ITEMS
                                .WORK_ITEM_ID
                                .eq(id)
                                .and(WORK_ITEMS.STATUS.eq(WorkItemStatus.WAITING)))
                .execute();
    }

    void openAttention(
            long caseId,
            long workItemId,
            String reason,
            String title,
            String question,
            String consequence) {
        var a = ATTENTION_REQUESTS;
        var reasonType = AttentionReasonType.valueOf(reason);
        dsl.insertInto(
                        a,
                        a.CASE_ID,
                        a.WORK_ITEM_ID,
                        a.REASON_TYPE,
                        a.TITLE,
                        a.QUESTION,
                        a.CONSEQUENCE,
                        a.STATUS)
                .select(
                        select(
                                        val(caseId),
                                        val(workItemId),
                                        val(reasonType),
                                        val(title),
                                        val(question),
                                        val(consequence),
                                        val(AttentionRequestStatus.OPEN))
                                .where(
                                        notExists(
                                                selectOne()
                                                        .from(a)
                                                        .where(
                                                                a.WORK_ITEM_ID
                                                                        .eq(workItemId)
                                                                        .and(
                                                                                a.STATUS.eq(
                                                                                        AttentionRequestStatus
                                                                                                .OPEN))
                                                                        .and(
                                                                                a.REASON_TYPE.eq(
                                                                                        reasonType))))))
                .execute();
    }

    boolean lockAndCheckActiveAgent(String key) {
        return dsl.select(AGENTS.IS_ACTIVE)
                .from(AGENTS)
                .where(AGENTS.AGENT_KEY.eq(key))
                .forShare()
                .fetchOptional(AGENTS.IS_ACTIVE)
                .orElse(false);
    }

    List<Map<String, Object>> currentTerminalDependencyStates(Instant observedAt) {
        var wc = WAITING_CONDITIONS.as("wc");
        var waiting = WORK_ITEMS.as("waiting_item");
        var dependency = WORK_ITEMS.as("dependency");
        return dsl.select(dependency.WORK_ITEM_REF, dependency.STATUS)
                .from(wc)
                .join(waiting)
                .on(waiting.WORK_ITEM_ID.eq(wc.WORK_ITEM_ID))
                .join(dependency)
                .on(
                        dependency.WORK_ITEM_REF.eq(
                                coalesce(
                                        jsonbGetAttributeAsText(
                                                wc.CONDITION_PAYLOAD, "dependent_wi_ref"),
                                        jsonbGetAttributeAsText(
                                                wc.CONDITION_PAYLOAD, "dependentWiRef"))))
                .where(
                        wc.STATUS
                                .eq(WaitingStatus.ACTIVE)
                                .and(waiting.STATUS.eq(WorkItemStatus.WAITING))
                                .and(wc.CONDITION_TYPE.eq(WaitingConditionType.DEPENDENCY_DONE))
                                .and(
                                        dependency.STATUS.in(
                                                WorkItemStatus.DONE, WorkItemStatus.CANCELLED)))
                .orderBy(dependency.WORK_ITEM_ID)
                .fetch(
                        r ->
                                Map.<String, Object>of(
                                        "workItemRef",
                                        r.value1(),
                                        "status",
                                        r.value2().getLiteral(),
                                        "observedAt",
                                        observedAt.toString()));
    }

    public boolean lockCaseIsActive(long caseId) {
        var status = dsl.select(CASES.STATUS).from(CASES)
                .where(CASES.CASE_ID.eq(caseId)).forShare().fetchOne(CASES.STATUS);
        return status != null && status != com.mulinocoreano.backend.generated.enums.CaseStatus.RESOLVED
                && status != com.mulinocoreano.backend.generated.enums.CaseStatus.CLOSED;
    }

    List<WaitingCandidate> loadCandidates(
            EventScope scope, boolean dependencyEvent, String dependencyRef, boolean lockRows) {
        var wc = WAITING_CONDITIONS.as("wc");
        var wi = WORK_ITEMS.as("wi");
        var c = CASES.as("c");
        var a = AGENTS.as("a");
        var filter =
                wc.STATUS
                        .eq(WaitingStatus.ACTIVE)
                        .and(wc.RESOLVED_BY_EVENT_ID.isNull())
                        .and(wi.STATUS.eq(WorkItemStatus.WAITING))
                        .and(c.STATUS.notIn(CaseStatus.RESOLVED, CaseStatus.CLOSED))
                        .and(notExists(selectOne().from(REPLENISHMENT_FOLLOWUPS)
                                .where(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(wi.WORK_ITEM_ID))));
        // Dependency events intentionally find waiting items across all Cases.
        if (dependencyEvent)
            filter =
                    filter.and(wc.CONDITION_TYPE.eq(WaitingConditionType.DEPENDENCY_DONE))
                            .and(
                                    coalesce(
                                                    jsonbGetAttributeAsText(
                                                            wc.CONDITION_PAYLOAD,
                                                            "dependent_wi_ref"),
                                                    jsonbGetAttributeAsText(
                                                            wc.CONDITION_PAYLOAD, "dependentWiRef"))
                                            .eq(dependencyRef));
        else {
            if (scope.caseId() != null) filter = filter.and(wi.CASE_ID.eq(scope.caseId()));
            if (scope.workItemId() != null)
                filter = filter.and(wi.WORK_ITEM_ID.eq(scope.workItemId()));
        }
        var query =
                dsl.select(
                                wc.WAITING_CONDITION_ID,
                                wc.WAITING_REF,
                                wc.CONDITION_TYPE,
                                wc.CONDITION_PAYLOAD,
                                wi.WORK_ITEM_ID,
                                wi.WORK_ITEM_REF,
                                wi.CASE_ID,
                                c.CASE_REF,
                                a.AGENT_KEY,
                                wi.ASSIGNED_USER_ID)
                        .from(wc)
                        .join(wi)
                        .on(wi.WORK_ITEM_ID.eq(wc.WORK_ITEM_ID))
                        .join(c)
                        .on(c.CASE_ID.eq(wi.CASE_ID))
                        .leftJoin(a)
                        .on(a.AGENT_ID.eq(wi.ASSIGNED_AGENT_ID))
                        .where(filter)
                        .orderBy(wc.WAITING_CONDITION_ID);
        var rows = lockRows ? query.forUpdate().of(wc, wi).fetch() : query.fetch();
        return rows.map(
                r ->
                        new WaitingCandidate(
                                r.value1(),
                                r.value2(),
                                r.value3().getLiteral(),
                                parsePayload(r.value4()),
                                r.value5(),
                                r.value6(),
                                r.value7(),
                                r.value8(),
                                r.value9(),
                                r.value10()));
    }

    Optional<InsertedEvent> insertEvent(
            String type, String ref, EventScope scope, EventActor actor, String payload) {
        var e = EVENTS;
        return dsl.insertInto(e)
                .set(e.EVENT_TYPE, type)
                .set(e.EXTERNAL_REF, ref)
                .set(e.CASE_ID, scope.caseId())
                .set(e.WORK_ITEM_ID, scope.workItemId())
                .set(
                        e.ACTOR_TYPE,
                        actor.actorType() == null ? null : ActorType.valueOf(actor.actorType()))
                .set(e.USER_ID, actor.userId())
                .set(e.PAYLOAD, JSONB.valueOf(payload))
                .onConflict(e.EVENT_TYPE, e.EXTERNAL_REF)
                .doNothing()
                .returningResult(e.EVENT_ID, e.OCCURRED_AT, e.PAYLOAD)
                .fetchOptional(
                        r ->
                                new InsertedEvent(
                                        r.value1(),
                                        Timestamp.valueOf(r.value2()).toInstant(),
                                        true,
                                        parsePayload(r.value3())));
    }

    ExistingEvent existingEvent(
            String type, String ref, EventScope scope, EventActor actor, String payload) {
        var e = EVENTS;
        var same =
                e.CASE_ID
                        .isNotDistinctFrom(scope.caseId())
                        .and(e.WORK_ITEM_ID.isNotDistinctFrom(scope.workItemId()))
                        .and(
                                e.ACTOR_TYPE.isNotDistinctFrom(
                                        actor.actorType() == null
                                                ? (ActorType) null
                                                : ActorType.valueOf(actor.actorType())))
                        .and(e.USER_ID.isNotDistinctFrom(actor.userId()))
                        .and(e.PAYLOAD.eq(JSONB.valueOf(payload)));
        return dsl.select(e.EVENT_ID, e.OCCURRED_AT, same, e.PAYLOAD)
                .from(e)
                .where(e.EVENT_TYPE.eq(type).and(e.EXTERNAL_REF.eq(ref)))
                .fetchSingle(
                        r ->
                                new ExistingEvent(
                                        r.value1(),
                                        Timestamp.valueOf(r.value2()).toInstant(),
                                        Boolean.TRUE.equals(r.value3()),
                                        parsePayload(r.value4())));
    }

    Optional<CaseScope> caseScope(String ref) {
        return dsl.select(CASES.CASE_ID, CASES.CASE_REF)
                .from(CASES)
                .where(CASES.CASE_REF.eq(ref))
                .fetchOptional(r -> new CaseScope(r.value1(), r.value2()));
    }

    Optional<WorkItemScope> workItemScope(String ref) {
        var w = WORK_ITEMS;
        return dsl.select(w.WORK_ITEM_ID, w.WORK_ITEM_REF, w.CASE_ID, CASES.CASE_REF)
                .from(w)
                .join(CASES)
                .on(CASES.CASE_ID.eq(w.CASE_ID))
                .where(w.WORK_ITEM_REF.eq(ref))
                .fetchOptional(
                        r -> new WorkItemScope(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    Optional<DependencySource> lockDependency(String ref) {
        var w = WORK_ITEMS;
        return dsl.select(w.WORK_ITEM_ID, w.WORK_ITEM_REF, w.CASE_ID, CASES.CASE_REF, w.STATUS)
                .from(w)
                .join(CASES)
                .on(CASES.CASE_ID.eq(w.CASE_ID))
                .where(w.WORK_ITEM_REF.eq(ref))
                .forUpdate()
                .of(w)
                .fetchOptional(
                        r ->
                                new DependencySource(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5().getLiteral()));
    }

    Optional<AnsweredAttention> answeredAttention(long id) {
        var a = ATTENTION_REQUESTS;
        var w = WORK_ITEMS;
        return dsl.select(
                        a.CASE_ID,
                        CASES.CASE_REF,
                        a.WORK_ITEM_ID,
                        w.WORK_ITEM_REF,
                        a.RESOLVED_BY_USER_ID,
                        a.ANSWER_TEXT)
                .from(a)
                .join(CASES)
                .on(CASES.CASE_ID.eq(a.CASE_ID))
                .leftJoin(w)
                .on(w.WORK_ITEM_ID.eq(a.WORK_ITEM_ID))
                .where(
                        a.ATTENTION_REQUEST_ID
                                .eq(id)
                                .and(a.STATUS.eq(AttentionRequestStatus.ANSWERED))
                                .and(a.REASON_TYPE.eq(AttentionReasonType.AUTHORITY_REQUIRED))
                                .and(a.RESOLVED_BY_USER_ID.isNotNull())
                                .and(a.GOVERNANCE_ACTION_ID.isNull()))
                .forShare()
                .of(a)
                .fetchOptional(
                        r ->
                                new AnsweredAttention(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5(),
                                        r.value6()));
    }

    boolean isPurchaseApproval(long id) {
        return dsl.fetchExists(
                GOVERNANCE_ACTIONS,
                GOVERNANCE_ACTIONS
                        .GOVERNANCE_ACTION_ID
                        .eq(id)
                        .and(GOVERNANCE_ACTIONS.REPLENISHMENT_PLAN_ID.isNotNull()));
    }

    Optional<ApprovedGovernanceAction> approvedGovernanceAction(long id) {
        var a = GOVERNANCE_ACTIONS;
        var d = GOVERNANCE_DECISIONS;
        return dsl.select(a.RESOURCE_TYPE, a.RESOURCE_ID, d.DECIDED_BY)
                .from(a)
                .join(d)
                .on(d.GOVERNANCE_ACTION_ID.eq(a.GOVERNANCE_ACTION_ID))
                .where(
                        a.GOVERNANCE_ACTION_ID
                                .eq(id)
                                .and(a.STATUS.eq(GovernanceActionStatus.APPROVED))
                                .and(d.DECISION.eq(GovernanceDecisionType.APPROVE)))
                .orderBy(d.DECIDED_AT.desc(), d.GOVERNANCE_DECISION_ID.desc())
                .limit(1)
                .forShare()
                .of(a, d)
                .fetchOptional(
                        r -> new ApprovedGovernanceAction(r.value1(), r.value2(), r.value3()));
    }

    Optional<EventScope> caseScope(long id) {
        return dsl.select(CASES.CASE_ID, CASES.CASE_REF)
                .from(CASES)
                .where(CASES.CASE_ID.eq(id))
                .fetchOptional(r -> new EventScope(r.value1(), r.value2(), null, null));
    }

    Optional<EventScope> workItemScope(long id) {
        var w = WORK_ITEMS;
        return dsl.select(w.CASE_ID, CASES.CASE_REF, w.WORK_ITEM_ID, w.WORK_ITEM_REF)
                .from(w)
                .join(CASES)
                .on(CASES.CASE_ID.eq(w.CASE_ID))
                .where(w.WORK_ITEM_ID.eq(id))
                .fetchOptional(r -> new EventScope(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    Optional<PreparedApproval> purchaseApproval(long id) {
        var g = GOVERNANCE_ACTIONS.as("g");
        var c = CASES.as("c");
        var w = WORK_ITEMS.as("w");
        var p = REPLENISHMENT_PLANS.as("p");
        var d = GOVERNANCE_DECISIONS.as("d");
        var u = USERS.as("u");
        var latest = GOVERNANCE_DECISIONS.as("latest");
        return dsl.select(g.CASE_ID, c.CASE_REF, g.WORK_ITEM_ID, w.WORK_ITEM_REF, d.DECIDED_BY)
                .from(g)
                .join(c)
                .on(c.CASE_ID.eq(g.CASE_ID))
                .join(w)
                .on(w.WORK_ITEM_ID.eq(g.WORK_ITEM_ID).and(w.CASE_ID.eq(g.CASE_ID)))
                .join(p)
                .on(
                        p.REPLENISHMENT_PLAN_ID
                                .eq(g.REPLENISHMENT_PLAN_ID)
                                .and(p.CASE_ID.eq(g.CASE_ID)))
                .join(d)
                .on(d.GOVERNANCE_ACTION_ID.eq(g.GOVERNANCE_ACTION_ID))
                .join(u)
                .on(u.USER_ID.eq(d.DECIDED_BY))
                .where(
                        g.GOVERNANCE_ACTION_ID
                                .eq(id)
                                .and(g.STATUS.eq(GovernanceActionStatus.APPROVED))
                                .and(g.REQUIRED_ROLE.eq(UserRole.MANAGER))
                                .and(g.RESOURCE_TYPE.eq("REPLENISHMENT_PLAN"))
                                .and(g.RESOURCE_ID.eq(p.REPLENISHMENT_PLAN_ID))
                                .and(d.IS_FINAL.isTrue())
                                .and(d.DECISION.eq(GovernanceDecisionType.APPROVE))
                                .and(u.ROLE.eq(UserRole.MANAGER))
                                .and(u.IS_ACTIVE.isTrue())
                                .and(
                                        d.GOVERNANCE_DECISION_ID.eq(
                                                select(latest.GOVERNANCE_DECISION_ID)
                                                        .from(latest)
                                                        .where(
                                                                latest.GOVERNANCE_ACTION_ID.eq(
                                                                        g.GOVERNANCE_ACTION_ID))
                                                        .orderBy(
                                                                latest.DECIDED_AT.desc(),
                                                                latest.GOVERNANCE_DECISION_ID
                                                                        .desc())
                                                        .limit(1))))
                .forShare()
                .of(g, d, u)
                .fetchOptional(
                        r ->
                                new PreparedApproval(
                                        new EventScope(
                                                r.value1(), r.value2(), r.value3(), r.value4()),
                                        new EventActor("USER", r.value5()),
                                        false));
    }

    Optional<ClaimEvidenceTarget> claimEvidenceTarget(long claimId, String ref) {
        var c = CLAIMS;
        var e = EVIDENCE;
        return dsl.select(c.CASE_ID, CASES.CASE_REF, e.EVIDENCE_ID, e.CASE_ID)
                .from(c)
                .join(CASES)
                .on(CASES.CASE_ID.eq(c.CASE_ID))
                .crossJoin(e)
                .where(c.CLAIM_ID.eq(claimId).and(e.EVIDENCE_REF.eq(ref)))
                .forShare()
                .of(c, e)
                .fetchOptional(
                        r ->
                                new ClaimEvidenceTarget(
                                        r.value1(), r.value2(), r.value3(), r.value4()));
    }

    void linkClaimEvidence(long claimId, long evidenceId, String relation) {
        var ce = CLAIM_EVIDENCE;
        dsl.insertInto(ce)
                .set(ce.CLAIM_ID, claimId)
                .set(ce.EVIDENCE_ID, evidenceId)
                .set(ce.RELATION, relation)
                .onConflict(ce.CLAIM_ID, ce.EVIDENCE_ID, ce.RELATION)
                .doNothing()
                .execute();
    }

    private Map<String, Object> parsePayload(JSONB json) {
        if (json == null) return Map.of();
        try {
            Object parsed = objectMapper.readValue(json.data(), Object.class);
            if (!(parsed instanceof Map<?, ?> source)) return Map.of();
            Map<String, Object> payload = new LinkedHashMap<>();
            source.forEach((key, value) -> payload.put(String.valueOf(key), value));
            return Collections.unmodifiableMap(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored event payload is not valid JSON", e);
        }
    }

    record EventScope(Long caseId, String caseRef, Long workItemId, String workItemRef) {}

    record CaseScope(long caseId, String caseRef) {}

    record WorkItemScope(long workItemId, String workItemRef, long caseId, String caseRef) {}

    record DependencySource(
            long workItemId, String workItemRef, long caseId, String caseRef, String status) {}

    record EventActor(String actorType, Long userId) {
        static EventActor none() {
            return new EventActor(null, null);
        }
    }

    record PreparedApproval(EventScope scope, EventActor actor, boolean globallyScopedGovernance) {}

    record PreparedClaimEvidence(
            EventScope scope, Long claimId, Long evidenceId, String relation) {}

    record ClaimEvidenceTarget(
            long claimCaseId, String caseRef, long evidenceId, Long evidenceCaseId) {}

    record AnsweredAttention(
            long caseId,
            String caseRef,
            Long workItemId,
            String workItemRef,
            long userId,
            String answerText) {}

    record ApprovedGovernanceAction(String resourceType, long resourceId, long userId) {}

    record InsertedEvent(
            long eventId, Instant occurredAt, boolean created, Map<String, Object> payload) {}

    record ExistingEvent(
            long eventId, Instant occurredAt, boolean sameContent, Map<String, Object> payload) {}

    record WaitingCandidate(
            long waitingId,
            String waitingRef,
            String conditionType,
            Map<String, Object> conditionPayload,
            long workItemId,
            String workItemRef,
            long caseId,
            String caseRef,
            String agentKey,
            Long assignedUserId) {}
}
