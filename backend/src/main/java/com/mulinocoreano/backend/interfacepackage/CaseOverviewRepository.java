package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import com.mulinocoreano.backend.generated.enums.*;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.*;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Explicit human-readable projections: execution credentials and source snapshots never leave here.
 */
@Repository
public class CaseOverviewRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;

    public CaseOverviewRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public List<CaseDto> search(String q, String sku, CaseStatus status) {
        Condition filter = status == null ? noCondition() : CASES.STATUS.eq(status);
        if (q != null)
            filter =
                    filter.and(
                            position(lower(CASES.TITLE), lower(val(q)))
                                    .gt(0)
                                    .or(position(lower(CASES.OBJECTIVE), lower(val(q))).gt(0))
                                    .or(position(lower(CASES.CASE_REF), lower(val(q))).gt(0)));
        if (sku != null) {
            var scope =
                    JSONB.valueOf(
                            json.write(
                                    Map.of("replenishment", Map.of("productSkus", List.of(sku)))));
            var planScope =
                    JSONB.valueOf(json.write(Map.of("products", List.of(Map.of("sku", sku)))));
            filter =
                    filter.and(
                            condition("{0} @> {1}", CASES.METADATA, val(scope))
                                    .or(
                                            exists(
                                                    selectOne()
                                                            .from(REPLENISHMENT_PLANS)
                                                            .where(
                                                                    REPLENISHMENT_PLANS.CASE_ID.eq(
                                                                            CASES.CASE_ID))
                                                            .and(
                                                                    condition(
                                                                            "{0} @> {1}",
                                                                            REPLENISHMENT_PLANS
                                                                                    .SOURCE_SNAPSHOT,
                                                                            val(planScope))))));
        }
        return dsl.select(CASES.fields())
                .from(CASES)
                .where(filter)
                .orderBy(CASES.OPENED_AT.desc(), CASES.CASE_ID.desc())
                .limit(100)
                .fetch(
                        r ->
                                new CaseDto(
                                        r.get(CASES.CASE_ID),
                                        r.get(CASES.CASE_REF),
                                        r.get(CASES.TITLE),
                                        r.get(CASES.OBJECTIVE),
                                        r.get(CASES.STATUS).getLiteral(),
                                        r.get(CASES.INTENT_TYPE).getLiteral(),
                                        java.sql.Timestamp.valueOf(r.get(CASES.OPENED_AT))
                                                .toInstant(),
                                        json.readTree(
                                                r.get(CASES.METADATA) == null
                                                        ? "{}"
                                                        : r.get(CASES.METADATA).data()),
                                        false));
    }

    public List<Map<String, Object>> participants(long id) {
        var p = CASE_PARTICIPANTS;
        return maps(
                dsl.select(
                                p.CASE_PARTICIPANT_ID,
                                p.ACTOR_TYPE,
                                p.AGENT_ID,
                                p.USER_ID,
                                p.ROLE,
                                AGENTS.DISPLAY_NAME.as("agent_name"),
                                USERS.NAME.as("user_name"),
                                p.ADDED_AT)
                        .from(p)
                        .leftJoin(AGENTS)
                        .on(AGENTS.AGENT_ID.eq(p.AGENT_ID))
                        .leftJoin(USERS)
                        .on(USERS.USER_ID.eq(p.USER_ID))
                        .where(p.CASE_ID.eq(id))
                        .orderBy(p.CASE_PARTICIPANT_ID)
                        .fetch());
    }

    public List<Map<String, Object>> workItems(long id) {
        var w = WORK_ITEMS;
        var rows =
                maps(
                        dsl.select(
                                        w.WORK_ITEM_ID,
                                        w.WORK_ITEM_REF,
                                        w.TITLE,
                                        w.DESCRIPTION,
                                        w.STATUS,
                                        w.ASSIGNED_AGENT_ID,
                                        w.ASSIGNED_USER_ID,
                                        AGENTS.DISPLAY_NAME.as("agent_name"),
                                        USERS.NAME.as("user_name"),
                                        w.DUE_AT,
                                        w.PROCUREMENT_PLAN_ID,
                                        w.PROCUREMENT_OUTCOME,
                                        w.LATEST_PLANNING_OUTCOME,
                                        w.LATEST_PLANNING_PLAN_ID,
                                        w.CREATED_AT,
                                        w.RESOLVED_AT)
                                .from(w)
                                .leftJoin(AGENTS)
                                .on(AGENTS.AGENT_ID.eq(w.ASSIGNED_AGENT_ID))
                                .leftJoin(USERS)
                                .on(USERS.USER_ID.eq(w.ASSIGNED_USER_ID))
                                .where(w.CASE_ID.eq(id))
                                .orderBy(w.WORK_ITEM_ID)
                                .fetch());
        var waits = WAITING_CONDITIONS;
        var all =
                maps(
                        dsl.select(
                                        waits.WAITING_CONDITION_ID,
                                        waits.WAITING_REF,
                                        waits.WORK_ITEM_ID,
                                        waits.CONDITION_TYPE,
                                        waits.REASON,
                                        waits.STATUS,
                                        waits.CREATED_AT)
                                .from(waits)
                                .join(w)
                                .on(w.WORK_ITEM_ID.eq(waits.WORK_ITEM_ID))
                                .where(w.CASE_ID.eq(id))
                                .and(waits.STATUS.eq(WaitingStatus.ACTIVE))
                                .orderBy(waits.WAITING_CONDITION_ID)
                                .fetch());
        rows.forEach(
                row ->
                        row.put(
                                "activeWaits",
                                all.stream()
                                        .filter(
                                                wait ->
                                                        Objects.equals(
                                                                wait.get("workItemId"),
                                                                row.get("workItemId")))
                                        .toList()));
        return rows;
    }

    public List<Map<String, Object>> attention(long id) {
        var a = ATTENTION_REQUESTS;
        return maps(
                dsl.select(
                                a.ATTENTION_REQUEST_ID,
                                a.VERSION,
                                CASES.CASE_REF,
                                WORK_ITEMS.WORK_ITEM_REF,
                                a.REASON_TYPE,
                                a.TITLE,
                                a.QUESTION,
                                a.CONSEQUENCE,
                                a.STATUS,
                                a.SUGGESTED_SCOPE,
                                a.GOVERNANCE_ACTION_ID,
                                a.ANSWER_TEXT.as("answer"),
                                a.ANSWER_SCOPE,
                                a.RESOLVED_BY_USER_ID,
                                a.RESOLVED_AT,
                                a.CREATED_AT)
                        .from(a)
                        .join(CASES)
                        .on(CASES.CASE_ID.eq(a.CASE_ID))
                        .leftJoin(WORK_ITEMS)
                        .on(WORK_ITEMS.WORK_ITEM_ID.eq(a.WORK_ITEM_ID))
                        .where(a.CASE_ID.eq(id))
                        .orderBy(a.ATTENTION_REQUEST_ID)
                        .fetch());
    }

    public List<Map<String, Object>> plans(long id) {
        var p = REPLENISHMENT_PLANS;
        return maps(
                dsl.select(
                                p.REPLENISHMENT_PLAN_ID,
                                p.PLAN_REF,
                                p.VERSION,
                                p.WAREHOUSE_ID,
                                p.AS_OF,
                                p.TARGET_DATE,
                                p.HORIZON_DAYS,
                                p.PLAN_HASH,
                                p.SOURCE_HASH,
                                p.CREATED_AT,
                                field("{0}->>'status'", String.class, p.RESULT).as("status"),
                                PLANNING_CASES.STATUS.as("planning_case_status"))
                        .from(p)
                        .join(PLANNING_CASES)
                        .on(PLANNING_CASES.CASE_ID.eq(p.CASE_ID))
                        .where(p.CASE_ID.eq(id))
                        .orderBy(p.VERSION.desc())
                        .fetch());
    }

    public List<Map<String, Object>> approvals(long id) {
        var g = GOVERNANCE_ACTIONS;
        var rows =
                maps(
                        dsl.select(
                                        g.GOVERNANCE_ACTION_ID,
                                        g.WORK_ITEM_ID,
                                        g.REPLENISHMENT_PLAN_ID,
                                        g.PROPOSAL_VERSION,
                                        g.PROPOSAL_HASH,
                                        g.ACTION_TYPE,
                                        g.STATUS,
                                        field("{0}->'orders'", JSONB.class, g.PAYLOAD)
                                                .as("proposed_orders"),
                                        field("{0}->'totalKrw'", JSONB.class, g.PAYLOAD)
                                                .as("total_krw"),
                                        g.REQUIRED_ROLE,
                                        g.PROPOSED_BY_AGENT_ID,
                                        g.REQUESTED_AT,
                                        g.EXPIRES_AT)
                                .from(g)
                                .where(g.CASE_ID.eq(id))
                                .orderBy(g.GOVERNANCE_ACTION_ID.desc())
                                .fetch());
        var a = PURCHASE_APPLICATIONS;
        var po = PURCHASE_ORDERS;
        var orders =
                maps(
                        dsl.select(
                                        a.GOVERNANCE_ACTION_ID,
                                        a.PURCHASE_APPLICATION_ID,
                                        po.PURCHASE_ORDER_ID,
                                        po.STATUS,
                                        po.SUPPLIER_ID,
                                        po.WAREHOUSE_ID,
                                        po.ORDER_DATE,
                                        po.EXPECTED_DELIVERY_DATE)
                                .from(a)
                                .join(po)
                                .on(po.PURCHASE_APPLICATION_ID.eq(a.PURCHASE_APPLICATION_ID))
                                .where(a.CASE_ID.eq(id))
                                .orderBy(po.PURCHASE_ORDER_ID)
                                .fetch());
        rows.forEach(
                row ->
                        row.put(
                                "purchaseOrders",
                                orders.stream()
                                        .filter(
                                                order ->
                                                        Objects.equals(
                                                                order.get("governanceActionId"),
                                                                row.get("governanceActionId")))
                                        .toList()));
        return rows;
    }

    public List<Map<String, Object>> decisions(long id) {
        var d = DECISIONS;
        var rows =
                maps(
                        dsl.select(
                                        d.DECISION_ID,
                                        d.WORK_ITEM_ID,
                                        d.DECISION_TEXT,
                                        d.SCOPE,
                                        d.DECIDED_BY_USER_ID,
                                        USERS.NAME.as("user_name"),
                                        d.DECIDED_AT,
                                        jsonbGetAttributeAsText(d.METADATA, "sourceAttentionId")
                                                .as("source_attention_id"))
                                .from(d)
                                .join(USERS)
                                .on(USERS.USER_ID.eq(d.DECIDED_BY_USER_ID))
                                .where(d.CASE_ID.eq(id))
                                .orderBy(d.DECISION_ID)
                                .fetch());
        rows.forEach(row -> row.put("kind", "CASE_DECISION"));
        var g = GOVERNANCE_DECISIONS;
        var governance =
                maps(
                        dsl.select(
                                        g.GOVERNANCE_DECISION_ID,
                                        g.GOVERNANCE_ACTION_ID,
                                        g.DECISION,
                                        g.REASON,
                                        g.DECIDED_BY.as("decided_by_user_id"),
                                        USERS.NAME.as("user_name"),
                                        g.DECIDED_AT,
                                        g.IS_FINAL)
                                .from(g)
                                .join(GOVERNANCE_ACTIONS)
                                .on(
                                        GOVERNANCE_ACTIONS.GOVERNANCE_ACTION_ID.eq(
                                                g.GOVERNANCE_ACTION_ID))
                                .join(USERS)
                                .on(USERS.USER_ID.eq(g.DECIDED_BY))
                                .where(GOVERNANCE_ACTIONS.CASE_ID.eq(id))
                                .orderBy(g.GOVERNANCE_DECISION_ID)
                                .fetch());
        governance.forEach(
                row -> {
                    row.put("kind", "GOVERNANCE_DECISION");
                    row.put("scope", "THIS_ACTION");
                });
        rows.addAll(governance);
        return rows;
    }

    public List<Map<String, Object>> evidence(long id) {
        var e = EVIDENCE;
        return maps(
                dsl.select(
                                e.EVIDENCE_ID,
                                e.EVIDENCE_REF,
                                e.SOURCE_TYPE,
                                e.TITLE,
                                e.CONTENT_URI,
                                e.CONTENT_HASH,
                                e.OBSERVED_AT)
                        .from(e)
                        .where(e.CASE_ID.eq(id))
                        .orderBy(e.EVIDENCE_ID)
                        .fetch());
    }

    public List<Map<String, Object>> claims(long id) {
        var c = CLAIMS;
        var rows =
                maps(
                        dsl.select(
                                        c.CLAIM_ID,
                                        c.SUBJECT_TYPE,
                                        c.SUBJECT_REF,
                                        c.CLAIM_TEXT,
                                        c.STATUS,
                                        c.ASSERTED_BY_AGENT_ID,
                                        c.ASSERTED_BY_USER_ID,
                                        c.ASSERTED_AT,
                                        c.RESOLVED_AT)
                                .from(c)
                                .where(c.CASE_ID.eq(id))
                                .orderBy(c.CLAIM_ID)
                                .fetch());
        var ce = CLAIM_EVIDENCE;
        var links =
                maps(
                        dsl.select(ce.CLAIM_ID, ce.EVIDENCE_ID, EVIDENCE.EVIDENCE_REF, ce.RELATION)
                                .from(ce)
                                .join(c)
                                .on(c.CLAIM_ID.eq(ce.CLAIM_ID))
                                .join(EVIDENCE)
                                .on(EVIDENCE.EVIDENCE_ID.eq(ce.EVIDENCE_ID))
                                .where(c.CASE_ID.eq(id))
                                .orderBy(ce.CLAIM_ID, ce.EVIDENCE_ID, ce.RELATION)
                                .fetch());
        rows.forEach(
                row ->
                        row.put(
                                "evidenceLinks",
                                links.stream()
                                        .filter(
                                                link ->
                                                        Objects.equals(
                                                                link.get("claimId"),
                                                                row.get("claimId")))
                                        .toList()));
        return rows;
    }

    public Map<String, Object> timeline(long id) {
        var e = EVENTS;
        long total = dsl.fetchCount(e, e.CASE_ID.eq(id));
        var items =
                maps(
                        dsl.select(
                                        e.EVENT_ID,
                                        e.EVENT_TYPE,
                                        e.WORK_ITEM_ID,
                                        e.ACTOR_TYPE,
                                        e.AGENT_ID,
                                        e.USER_ID,
                                        e.OCCURRED_AT)
                                .from(e)
                                .where(e.CASE_ID.eq(id))
                                .orderBy(e.OCCURRED_AT.desc(), e.EVENT_ID.desc())
                                .limit(100)
                                .fetch());
        return Map.of("items", items, "totalCount", total, "truncated", total > items.size());
    }

    public Map<Long, Map<String, Object>> summaries(List<CaseDto> cases) {
        var ids = cases.stream().map(CaseDto::caseId).toList();
        var works =
                maps(
                        dsl.select(WORK_ITEMS.CASE_ID, WORK_ITEMS.STATUS, WORK_ITEMS.TITLE)
                                .from(WORK_ITEMS)
                                .where(WORK_ITEMS.CASE_ID.in(ids))
                                .and(
                                        WORK_ITEMS.STATUS.notIn(
                                                WorkItemStatus.DONE, WorkItemStatus.CANCELLED))
                                .orderBy(WORK_ITEMS.WORK_ITEM_ID)
                                .fetch());
        var attention =
                maps(
                        dsl.select(ATTENTION_REQUESTS.CASE_ID, ATTENTION_REQUESTS.QUESTION)
                                .from(ATTENTION_REQUESTS)
                                .where(ATTENTION_REQUESTS.CASE_ID.in(ids))
                                .and(ATTENTION_REQUESTS.STATUS.eq(AttentionRequestStatus.OPEN))
                                .orderBy(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID)
                                .fetch());
        var result = new HashMap<Long, Map<String, Object>>();
        for (var c : cases) {
            var remaining =
                    works.stream()
                            .filter(w -> Objects.equals(w.get("caseId"), c.caseId()))
                            .toList();
            var questions =
                    attention.stream()
                            .filter(a -> Objects.equals(a.get("caseId"), c.caseId()))
                            .map(a -> a.get("question").toString())
                            .toList();
            result.put(c.caseId(), summary(c.status(), remaining, questions));
        }
        return result;
    }

    static Map<String, Object> summary(
            String state, List<Map<String, Object>> remaining, List<String> questions) {
        var next = new ArrayList<>(questions);
        remaining.forEach(w -> next.add("미완료 업무: " + w.get("title")));
        return Map.of(
                "state",
                state,
                "needsHumanAttention",
                !questions.isEmpty(),
                "activeWorkCount",
                remaining.stream()
                        .filter(w -> Set.of("READY", "IN_PROGRESS").contains(w.get("status")))
                        .count(),
                "remainingWorkCount",
                remaining.size(),
                "nextActions",
                next);
    }

    private List<Map<String, Object>> maps(Result<? extends Record> rows) {
        var result = new ArrayList<Map<String, Object>>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var f : r.fields()) {
                Object v = r.get(f);
                if (v instanceof EnumType e) v = e.getLiteral();
                if (v instanceof JSONB b) v = json.readTree(b.data());
                m.put(camel(f.getName()), v);
            }
            result.add(m);
        }
        return result;
    }

    private String camel(String name) {
        var parts = name.split("_");
        var result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++)
            result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        return result.toString();
    }
}
