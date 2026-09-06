package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import com.mulinocoreano.backend.generated.enums.ActorType;
import com.mulinocoreano.backend.generated.enums.WaitingConditionType;
import com.mulinocoreano.backend.generated.enums.WaitingStatus;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.Select;
import org.springframework.stereotype.Repository;

/** Builds all dynamic layers in one statement, sharing PostgreSQL's statement snapshot. */
@Repository
public class ContextSnapshotRepository {
    private final DSLContext dsl;

    public ContextSnapshotRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String reconstruct(String caseRef) {
        var c = CASES;
        var snapshot =
                jsonbObject(
                        key("objective").value(c.OBJECTIVE),
                        key("obligation").value(obligations()),
                        key("organizational").value(participants()),
                        key("business")
                                .value(jsonbObject(key("references").value(businessReferences()))),
                        key("epistemic")
                                .value(
                                        jsonbObject(
                                                key("evidence").value(evidence()),
                                                key("claims").value(claims()),
                                                key("decisions").value(decisions()))),
                        key("control")
                                .value(jsonbObject(key("governance").value("see docs/02_flow.md"))),
                        // Transaction timestamps can predate reconstruction; retain statement time.
                        key("reconstructed_at")
                                .value(
                                        function(
                                                "statement_timestamp",
                                                java.time.OffsetDateTime.class)),
                        key("stale").value(false));
        return dsl.select(snapshot)
                .from(c)
                .where(c.CASE_REF.eq(caseRef))
                .fetchSingle()
                .value1()
                .data();
    }

    private Field<JSONB> obligations() {
        var wi = WORK_ITEMS;
        var agent = AGENTS.as("assigned_agent");
        var user = USERS.as("assigned_user");
        var assignee =
                when(
                                wi.ASSIGNED_AGENT_ID.isNotNull(),
                                actor("AGENT", agent.AGENT_KEY, agent.DISPLAY_NAME))
                        .when(
                                wi.ASSIGNED_USER_ID.isNotNull(),
                                actor("USER", wi.ASSIGNED_USER_ID.cast(String.class), user.NAME));
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("ref").value(wi.WORK_ITEM_REF),
                                                        key("title").value(wi.TITLE),
                                                        key("description").value(wi.DESCRIPTION),
                                                        key("status")
                                                                .value(
                                                                        wi.STATUS.cast(
                                                                                String.class)),
                                                        key("priority")
                                                                .value(
                                                                        wi.PRIORITY.cast(
                                                                                String.class)),
                                                        key("due_at").value(wi.DUE_AT),
                                                        key("assignee").value(assignee),
                                                        key("waiting_conditions")
                                                                .value(waitingConditions()),
                                                        key("dependencies").value(dependencies())))
                                        .orderBy(wi.WORK_ITEM_ID))
                        .from(wi)
                        .leftJoin(agent)
                        .on(agent.AGENT_ID.eq(wi.ASSIGNED_AGENT_ID))
                        .leftJoin(user)
                        .on(user.USER_ID.eq(wi.ASSIGNED_USER_ID))
                        .where(wi.CASE_ID.eq(CASES.CASE_ID)));
    }

    private Field<JSONB> waitingConditions() {
        var wc = WAITING_CONDITIONS;
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("ref").value(wc.WAITING_REF),
                                                        key("type")
                                                                .value(
                                                                        wc.CONDITION_TYPE.cast(
                                                                                String.class)),
                                                        key("status")
                                                                .value(
                                                                        wc.STATUS.cast(
                                                                                String.class)),
                                                        key("reason").value(wc.REASON),
                                                        key("payload")
                                                                .value(
                                                                        coalesce(
                                                                                wc.CONDITION_PAYLOAD,
                                                                                inline(
                                                                                        JSONB
                                                                                                .valueOf(
                                                                                                        "{}")))),
                                                        key("created_at").value(wc.CREATED_AT)))
                                        .orderBy(wc.WAITING_CONDITION_ID))
                        .from(wc)
                        .where(wc.WORK_ITEM_ID.eq(WORK_ITEMS.WORK_ITEM_ID))
                        .and(wc.STATUS.eq(WaitingStatus.ACTIVE)));
    }

    private Field<JSONB> dependencies() {
        var wc = WAITING_CONDITIONS;
        return array(
                select(
                                jsonbArrayAgg(
                                                coalesce(
                                                        jsonbGetAttributeAsText(
                                                                wc.CONDITION_PAYLOAD,
                                                                "dependent_wi_ref"),
                                                        jsonbGetAttributeAsText(
                                                                wc.CONDITION_PAYLOAD,
                                                                "dependentWiRef")))
                                        .orderBy(wc.WAITING_CONDITION_ID))
                        .from(wc)
                        .where(wc.WORK_ITEM_ID.eq(WORK_ITEMS.WORK_ITEM_ID))
                        .and(wc.STATUS.eq(WaitingStatus.ACTIVE))
                        .and(wc.CONDITION_TYPE.eq(WaitingConditionType.DEPENDENCY_DONE))
                        .and(
                                function("jsonb_typeof", String.class, wc.CONDITION_PAYLOAD)
                                        .eq("object"))
                        .and(
                                jsonbKeyExists(wc.CONDITION_PAYLOAD, "dependent_wi_ref")
                                        .or(
                                                jsonbKeyExists(
                                                        wc.CONDITION_PAYLOAD, "dependentWiRef"))));
    }

    private Field<JSONB> participants() {
        var cp = CASE_PARTICIPANTS;
        var agent = AGENTS.as("participant_agent");
        var user = USERS.as("participant_user");
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("actor_type")
                                                                .value(
                                                                        cp.ACTOR_TYPE.cast(
                                                                                String.class)),
                                                        key("actor_ref")
                                                                .value(
                                                                        when(
                                                                                        cp
                                                                                                .ACTOR_TYPE
                                                                                                .eq(
                                                                                                        ActorType
                                                                                                                .AGENT),
                                                                                        agent.AGENT_KEY)
                                                                                .when(
                                                                                        cp
                                                                                                .ACTOR_TYPE
                                                                                                .eq(
                                                                                                        ActorType
                                                                                                                .USER),
                                                                                        cp.USER_ID
                                                                                                .cast(
                                                                                                        String
                                                                                                                .class))),
                                                        key("agent")
                                                                .value(
                                                                        when(
                                                                                cp.ACTOR_TYPE.eq(
                                                                                        ActorType
                                                                                                .AGENT),
                                                                                agent.AGENT_KEY)),
                                                        key("name")
                                                                .value(
                                                                        when(
                                                                                        cp
                                                                                                .ACTOR_TYPE
                                                                                                .eq(
                                                                                                        ActorType
                                                                                                                .AGENT),
                                                                                        agent.DISPLAY_NAME)
                                                                                .when(
                                                                                        cp
                                                                                                .ACTOR_TYPE
                                                                                                .eq(
                                                                                                        ActorType
                                                                                                                .USER),
                                                                                        user.NAME)),
                                                        key("role").value(cp.ROLE)))
                                        .orderBy(cp.CASE_PARTICIPANT_ID))
                        .from(cp)
                        .leftJoin(agent)
                        .on(agent.AGENT_ID.eq(cp.AGENT_ID))
                        .leftJoin(user)
                        .on(user.USER_ID.eq(cp.USER_ID))
                        .where(cp.CASE_ID.eq(CASES.CASE_ID)));
    }

    private Field<JSONB> businessReferences() {
        var wi = WORK_ITEMS;
        var ref = jsonbGetAttribute(wi.METADATA, "businessRef");
        return array(
                select(jsonbArrayAgg(ref).orderBy(wi.WORK_ITEM_ID))
                        .from(wi)
                        .where(wi.CASE_ID.eq(CASES.CASE_ID))
                        .and(jsonbKeyExists(wi.METADATA, "businessRef"))
                        .and(ref.ne(inline(JSONB.valueOf("null")))));
    }

    private Field<JSONB> evidence() {
        var e = EVIDENCE;
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("ref").value(e.EVIDENCE_REF),
                                                                key("source_type")
                                                                        .value(e.SOURCE_TYPE),
                                                        key("title").value(e.TITLE),
                                                                key("content_uri")
                                                                        .value(e.CONTENT_URI),
                                                        key("content_hash").value(e.CONTENT_HASH),
                                                                key("observed_at")
                                                                        .value(e.OBSERVED_AT)))
                                        .orderBy(e.EVIDENCE_ID))
                        .from(e)
                        .where(e.CASE_ID.eq(CASES.CASE_ID)));
    }

    private Field<JSONB> claims() {
        var claim = CLAIMS;
        var agent = AGENTS.as("claim_agent");
        var user = USERS.as("claim_user");
        var run = RUNS.as("claim_run");
        var runAgent = AGENTS.as("run_agent");
        var assertedBy =
                when(
                                claim.ASSERTED_BY_AGENT_ID.isNotNull(),
                                assertionActor(
                                        "AGENT", agent.AGENT_KEY, agent.DISPLAY_NAME, run.RUN_REF))
                        .when(
                                claim.ASSERTED_BY_USER_ID.isNotNull(),
                                assertionActor(
                                        "USER",
                                        claim.ASSERTED_BY_USER_ID.cast(String.class),
                                        user.NAME,
                                        run.RUN_REF))
                        .when(
                                claim.ASSERTED_BY_RUN_ID.isNotNull(),
                                assertionActor(
                                        "AGENT",
                                        runAgent.AGENT_KEY,
                                        runAgent.DISPLAY_NAME,
                                        run.RUN_REF));
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("claim_id").value(claim.CLAIM_ID),
                                                        key("subject_type")
                                                                .value(claim.SUBJECT_TYPE),
                                                        key("subject_ref").value(claim.SUBJECT_REF),
                                                        key("claim_text").value(claim.CLAIM_TEXT),
                                                        key("status")
                                                                .value(
                                                                        claim.STATUS.cast(
                                                                                String.class)),
                                                        key("asserted_by").value(assertedBy),
                                                        key("asserted_at").value(claim.ASSERTED_AT),
                                                        key("resolved_at").value(claim.RESOLVED_AT),
                                                        key("evidence").value(claimEvidence())))
                                        .orderBy(claim.CLAIM_ID))
                        .from(claim)
                        .leftJoin(agent)
                        .on(agent.AGENT_ID.eq(claim.ASSERTED_BY_AGENT_ID))
                        .leftJoin(user)
                        .on(user.USER_ID.eq(claim.ASSERTED_BY_USER_ID))
                        .leftJoin(run)
                        .on(run.RUN_ID.eq(claim.ASSERTED_BY_RUN_ID))
                        .leftJoin(runAgent)
                        .on(runAgent.AGENT_ID.eq(run.AGENT_ID))
                        .where(claim.CASE_ID.eq(CASES.CASE_ID)));
    }

    private Field<JSONB> claimEvidence() {
        var ce = CLAIM_EVIDENCE;
        var e = EVIDENCE.as("linked_evidence");
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("ref").value(e.EVIDENCE_REF),
                                                        key("relation").value(ce.RELATION)))
                                        .orderBy(e.EVIDENCE_ID, ce.RELATION))
                        .from(ce)
                        .join(e)
                        .on(e.EVIDENCE_ID.eq(ce.EVIDENCE_ID))
                        .where(ce.CLAIM_ID.eq(CLAIMS.CLAIM_ID)));
    }

    private Field<JSONB> decisions() {
        var d = DECISIONS;
        var user = USERS.as("deciding_user");
        var wi = WORK_ITEMS.as("decision_work_item");
        return array(
                select(
                                jsonbArrayAgg(
                                                jsonbObject(
                                                        key("decision_id").value(d.DECISION_ID),
                                                        key("work_item_ref")
                                                                .value(wi.WORK_ITEM_REF),
                                                        key("decision_text").value(d.DECISION_TEXT),
                                                        key("scope")
                                                                .value(d.SCOPE.cast(String.class)),
                                                        key("decided_by")
                                                                .value(
                                                                        jsonbObject(
                                                                                key("user_id")
                                                                                        .value(
                                                                                                d.DECIDED_BY_USER_ID),
                                                                                key("name")
                                                                                        .value(
                                                                                                user.NAME))),
                                                        key("decided_at").value(d.DECIDED_AT)))
                                        .orderBy(d.DECISION_ID))
                        .from(d)
                        .join(user)
                        .on(user.USER_ID.eq(d.DECIDED_BY_USER_ID))
                        .leftJoin(wi)
                        .on(wi.WORK_ITEM_ID.eq(d.WORK_ITEM_ID))
                        .where(d.CASE_ID.eq(CASES.CASE_ID)));
    }

    private static Field<JSONB> actor(String type, Field<String> ref, Field<String> name) {
        return jsonbObject(
                key("actor_type").value(type),
                key("actor_ref").value(ref),
                key("name").value(name));
    }

    private static Field<JSONB> assertionActor(
            String type, Field<String> ref, Field<String> name, Field<String> runRef) {
        return jsonbObject(
                key("actor_type").value(type),
                key("actor_ref").value(ref),
                key("name").value(name),
                key("run_ref").value(runRef));
    }

    private static Field<JSONB> array(Select<? extends Record1<JSONB>> query) {
        return coalesce(field(query), inline(JSONB.valueOf("[]")));
    }
}
