package com.mulinocoreano.backend.procurement;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.ATTENTION_REQUESTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.CASE_PARTICIPANTS;
import static com.mulinocoreano.backend.generated.Tables.DECISIONS;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_ACTIONS;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_AUDIT_LOGS;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_DECISIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_APPLICATIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDERS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDER_ITEMS;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;
import static com.mulinocoreano.backend.generated.Tables.RUNS;
import static com.mulinocoreano.backend.generated.Tables.USERS;
import static com.mulinocoreano.backend.generated.Tables.WAITING_CONDITIONS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.execution.RunCapabilityAccess.RunScope;
import com.mulinocoreano.backend.generated.enums.ActorType;
import com.mulinocoreano.backend.generated.enums.AttentionReasonType;
import com.mulinocoreano.backend.generated.enums.AttentionRequestStatus;
import com.mulinocoreano.backend.generated.enums.DecisionScope;
import com.mulinocoreano.backend.generated.enums.GovernanceActionStatus;
import com.mulinocoreano.backend.generated.enums.GovernanceDecisionType;
import com.mulinocoreano.backend.generated.enums.PurchaseOrderStatus;
import com.mulinocoreano.backend.generated.enums.UserRole;
import com.mulinocoreano.backend.generated.enums.WaitingConditionType;
import com.mulinocoreano.backend.generated.enums.WaitingStatus;
import com.mulinocoreano.backend.generated.enums.WorkItemStatus;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.SQLDataType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.Map;

/** Typed purchasing persistence. Callers own transaction boundaries and business decisions. */
@Repository
public class PurchaseRepository {
    private static final Field<LocalDateTime> NOW =
            function(name("clock_timestamp"), SQLDataType.LOCALDATETIME);
    private final DSLContext dsl;
    private final CanonicalJson json;

    public PurchaseRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public boolean proposalExists(long planId) {
        return dsl.fetchExists(
                select(GOVERNANCE_ACTIONS.GOVERNANCE_ACTION_ID)
                        .from(GOVERNANCE_ACTIONS)
                        .where(GOVERNANCE_ACTIONS.REPLENISHMENT_PLAN_ID.eq(planId)));
    }

    public long insertProposal(
            PurchasePlan plan, RunScope scope, PurchaseBundle bundle, String hash) {
        var g = GOVERNANCE_ACTIONS;
        return dsl.insertInto(g)
                .set(g.REQUESTED_BY, plan.requester())
                .set(g.ACTION_TYPE, "PURCHASE_PROPOSAL")
                .set(g.RESOURCE_TYPE, "REPLENISHMENT_PLAN")
                .set(g.RESOURCE_ID, plan.id())
                .set(g.PAYLOAD, value(bundle))
                .set(g.REQUIRED_ROLE, UserRole.MANAGER)
                .set(g.CASE_ID, scope.caseId())
                .set(g.WORK_ITEM_ID, scope.workItemId())
                .set(g.REPLENISHMENT_PLAN_ID, plan.id())
                .set(
                        g.PROPOSED_BY_AGENT_ID,
                        field(
                                select(RUNS.AGENT_ID)
                                        .from(RUNS)
                                        .where(RUNS.RUN_ID.eq(scope.runId()))))
                .set(g.PROPOSAL_VERSION, plan.version())
                .set(g.PROPOSAL_HASH, hash)
                .returningResult(g.GOVERNANCE_ACTION_ID)
                .fetchSingle(g.GOVERNANCE_ACTION_ID);
    }

    public void lockWorkAndCase(PurchaseApproval approval) {
        dsl.select(WORK_ITEMS.WORK_ITEM_ID)
                .from(WORK_ITEMS)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(approval.workId()))
                .forUpdate()
                .fetchSingle();
        dsl.select(CASES.CASE_ID)
                .from(CASES)
                .where(CASES.CASE_ID.eq(approval.caseId()))
                .forShare()
                .fetchSingle();
    }

    public long nextApplicationId() {
        // SERIAL's sequence name is discovered from generated table/column names, not caller input.
        Field<String> sequence =
                function(
                        name("pg_get_serial_sequence"),
                        String.class,
                        val(PURCHASE_APPLICATIONS.getName()),
                        val(PURCHASE_APPLICATIONS.PURCHASE_APPLICATION_ID.getName()));
        Field<Long> next = function(name("nextval"), Long.class, sequence);
        return dsl.select(next).fetchSingle(next);
    }

    public void updateApprovalStatus(long id, String status) {
        dsl.update(GOVERNANCE_ACTIONS)
                .set(GOVERNANCE_ACTIONS.STATUS, GovernanceActionStatus.valueOf(status))
                .where(GOVERNANCE_ACTIONS.GOVERNANCE_ACTION_ID.eq(id))
                .execute();
    }

    public void cancelApprovalWork(long workId) {
        var wait = WAITING_CONDITIONS;
        dsl.update(wait)
                .set(wait.STATUS, WaitingStatus.CANCELLED)
                .set(wait.RESOLVED_AT, NOW)
                .where(wait.WORK_ITEM_ID.eq(workId))
                .and(wait.CONDITION_TYPE.eq(WaitingConditionType.APPROVAL))
                .and(wait.STATUS.eq(WaitingStatus.ACTIVE))
                .execute();
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.CANCELLED)
                .set(WORK_ITEMS.RESOLVED_AT, NOW)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(workId))
                .execute();
    }

    public void insertApplication(
            long applicationId,
            PurchaseApproval approval,
            long decisionId,
            long userId,
            JsonNode manifest) {
        var receipt =
                Map.of(
                        "manifest",
                        manifest,
                        "manifestHash",
                        json.sha256(manifest),
                        "proposalHash",
                        approval.hash());
        var app = PURCHASE_APPLICATIONS;
        dsl.insertInto(app)
                .set(app.PURCHASE_APPLICATION_ID, applicationId)
                .set(app.GOVERNANCE_ACTION_ID, approval.id())
                .set(app.GOVERNANCE_DECISION_ID, decisionId)
                .set(app.REPLENISHMENT_PLAN_ID, approval.planId())
                .set(app.CASE_ID, approval.caseId())
                .set(app.WORK_ITEM_ID, approval.workId())
                .set(app.APPLIED_BY_USER_ID, userId)
                .set(app.VERIFICATION_RECEIPT, value(receipt))
                .execute();
    }

    public void writeOrders(long applicationId, long userId, PurchaseBundle bundle) {
        var po = PURCHASE_ORDERS;
        for (var order : bundle.orders()) {
            long id =
                    dsl.insertInto(po)
                            .set(po.SUPPLIER_ID, order.supplierId())
                            .set(po.CREATED_BY, userId)
                            .set(po.ORDER_DATE, bundle.asOf())
                            .set(po.EXPECTED_DELIVERY_DATE, order.expectedDeliveryDate())
                            .set(po.STATUS, PurchaseOrderStatus.ORDERED)
                            .set(po.PURCHASE_APPLICATION_ID, applicationId)
                            .set(po.WAREHOUSE_ID, bundle.warehouseId())
                            .returningResult(po.PURCHASE_ORDER_ID)
                            .fetchSingle(po.PURCHASE_ORDER_ID);
            for (var line : order.lines()) insertLine(id, line);
        }
    }

    private void insertLine(long orderId, PurchaseBundle.Line line) {
        var item = PURCHASE_ORDER_ITEMS;
        dsl.insertInto(item)
                .set(item.PURCHASE_ORDER_ID, orderId)
                .set(item.RAW_MATERIAL_ID, line.materialId())
                .set(item.QUANTITY, line.baseQuantity())
                .set(item.UNIT_PRICE, line.baseUnitPrice())
                .set(item.PURCHASE_QUANTITY, line.buyQuantity())
                .set(item.PURCHASE_UNIT_PRICE, line.buyUnitPrice())
                .set(item.PURCHASE_UNIT, line.buyUnit())
                .set(item.BASE_UNIT, line.baseUnit())
                .set(item.BASE_QUANTITY_PER_PURCHASE_UNIT, line.baseUnitsPerBuyUnit())
                .set(item.LINE_AMOUNT, line.lineAmountKrw().toBigIntegerExact())
                .set(item.EXPECTED_DELIVERY_DATE, line.expectedDeliveryDate())
                .set(item.SUPPLIER_MATERIAL_TERM_ID, line.sourceTermId())
                .execute();
    }

    public boolean lockActiveManager(long userId) {
        return dsl.select(USERS.USER_ID)
                .from(USERS)
                .where(USERS.USER_ID.eq(userId))
                .and(USERS.ROLE.eq(UserRole.MANAGER))
                .and(USERS.IS_ACTIVE.isTrue())
                .forShare()
                .fetchOptional()
                .isPresent();
    }

    public void lockPlan(long planId) {
        dsl.select(REPLENISHMENT_PLANS.REPLENISHMENT_PLAN_ID)
                .from(REPLENISHMENT_PLANS)
                .where(REPLENISHMENT_PLANS.REPLENISHMENT_PLAN_ID.eq(planId))
                .forShare()
                .fetchSingle();
    }

    public PurchaseApproval action(long id, boolean lock) {
        var g = GOVERNANCE_ACTIONS.as("g");
        var p = REPLENISHMENT_PLANS.as("p");
        var query =
                dsl.select(
                                g.GOVERNANCE_ACTION_ID,
                                g.CASE_ID,
                                g.WORK_ITEM_ID,
                                p.PLAN_REF,
                                g.PROPOSAL_VERSION,
                                g.PROPOSAL_HASH,
                                g.STATUS,
                                g.REPLENISHMENT_PLAN_ID)
                        .from(g)
                        .join(p)
                        .on(p.REPLENISHMENT_PLAN_ID.eq(g.REPLENISHMENT_PLAN_ID))
                        .where(g.GOVERNANCE_ACTION_ID.eq(id));
        var row = lock ? query.forUpdate().of(g).fetchOptional() : query.fetchOptional();
        return row.map(
                        r ->
                                new PurchaseApproval(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5(),
                                        r.value6(),
                                        r.value7().getLiteral(),
                                        r.value8()))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Purchase approval is not available"));
    }

    public long recordDecision(
            PurchaseApproval approval, long userId, PurchaseDecisionRequest request) {
        var participant = CASE_PARTICIPANTS;
        dsl.insertInto(participant)
                .set(participant.CASE_ID, approval.caseId())
                .set(participant.ACTOR_TYPE, ActorType.USER)
                .set(participant.USER_ID, userId)
                .set(participant.ROLE, "구매 결정자")
                .onConflictDoNothing()
                .execute();
        var decision = GOVERNANCE_DECISIONS;
        long id =
                dsl.insertInto(decision)
                        .set(decision.GOVERNANCE_ACTION_ID, approval.id())
                        .set(decision.DECIDED_BY, userId)
                        .set(decision.DECISION, GovernanceDecisionType.valueOf(request.decision()))
                        .set(decision.REASON, request.reason())
                        .set(decision.IS_FINAL, true)
                        .returningResult(decision.GOVERNANCE_DECISION_ID)
                        .fetchSingle(decision.GOVERNANCE_DECISION_ID);
        var metadata =
                Map.of(
                        "approvalId",
                        approval.id(),
                        "version",
                        approval.version(),
                        "proposalHash",
                        approval.hash(),
                        "governanceDecisionId",
                        id);
        var history = DECISIONS;
        dsl.insertInto(history)
                .set(history.CASE_ID, approval.caseId())
                .set(history.WORK_ITEM_ID, approval.workId())
                .set(history.DECISION_TEXT, request.decision() + ": " + request.reason())
                .set(history.SCOPE, DecisionScope.THIS_ACTION)
                .set(history.DECIDED_BY_USER_ID, userId)
                .set(history.METADATA, value(metadata))
                .execute();
        return id;
    }

    public void setOutcome(long workId, long planId, String outcome) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.PROCUREMENT_PLAN_ID, planId)
                .set(WORK_ITEMS.PROCUREMENT_OUTCOME, outcome)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(workId))
                .execute();
    }

    public void resolveAttention(long approvalId, long userId, String reason, String status) {
        var attention = ATTENTION_REQUESTS;
        dsl.update(attention)
                .set(attention.STATUS, AttentionRequestStatus.valueOf(status))
                .set(attention.RESOLVED_BY_USER_ID, userId)
                .set(attention.ANSWER_TEXT, reason)
                .set(attention.ANSWER_SCOPE, DecisionScope.THIS_ACTION)
                .set(attention.RESOLVED_AT, NOW)
                .set(attention.VERSION, coalesce(attention.VERSION, 1).add(1))
                .where(attention.GOVERNANCE_ACTION_ID.eq(approvalId))
                .and(attention.STATUS.eq(AttentionRequestStatus.OPEN))
                .execute();
    }

    public void attention(
            long caseId, Long workId, String title, String question, Long approvalId) {
        var attention = ATTENTION_REQUESTS;
        dsl.insertInto(attention)
                .set(attention.CASE_ID, caseId)
                .set(attention.WORK_ITEM_ID, workId)
                .set(
                        attention.REASON_TYPE,
                        approvalId == null
                                ? AttentionReasonType.JUDGMENT_REQUIRED
                                : AttentionReasonType.AUTHORITY_REQUIRED)
                .set(attention.TITLE, title)
                .set(attention.QUESTION, question)
                .set(attention.CONSEQUENCE, "입고와 생산이 늦어지면 목표 재고를 확보하지 못할 수 있습니다.")
                .set(attention.SUGGESTED_SCOPE, DecisionScope.THIS_ACTION)
                .set(
                        attention.REQUESTED_BY_AGENT_ID,
                        field(
                                select(AGENTS.AGENT_ID)
                                        .from(AGENTS)
                                        .where(AGENTS.AGENT_KEY.eq("PROCUREMENT"))))
                .set(attention.GOVERNANCE_ACTION_ID, approvalId)
                .execute();
    }

    public void audit(
            long approvalId,
            Long userId,
            String event,
            String resource,
            long resourceId,
            JsonNode state) {
        var log = GOVERNANCE_AUDIT_LOGS;
        dsl.insertInto(log)
                .set(log.GOVERNANCE_ACTION_ID, approvalId)
                .set(log.ACTOR_ID, userId)
                .set(log.EVENT_TYPE, event)
                .set(log.RESOURCE_TYPE, resource)
                .set(log.RESOURCE_ID, resourceId)
                .set(log.AFTER_STATE, value(state))
                .execute();
    }

    private JSONB value(Object value) {
        return JSONB.valueOf(json.write(value));
    }
}
