package com.mulinocoreano.backend.procurement;

import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_ACTIONS;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_DECISIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_APPLICATIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDERS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDER_ITEMS;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.jsonEntry;
import static org.jooq.impl.DSL.jsonbArrayAgg;
import static org.jooq.impl.DSL.jsonbObject;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.generated.enums.GovernanceActionStatus;
import com.mulinocoreano.backend.generated.enums.GovernanceDecisionType;
import com.mulinocoreano.backend.generated.enums.PurchaseOrderStatus;
import com.mulinocoreano.backend.generated.enums.UserRole;
import com.mulinocoreano.backend.generated.enums.WorkItemStatus;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

/** Reads the exact rows used by deterministic completion verification. */
@Repository
public class PurchaseEvidenceRepository {
    private static final JSONB EMPTY_ARRAY = JSONB.valueOf("[]");
    private final DSLContext dsl;
    private final CanonicalJson json;

    public PurchaseEvidenceRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public JsonNode manifest(long applicationId) {
        var p = PURCHASE_ORDERS.as("po");
        var i = PURCHASE_ORDER_ITEMS.as("item");
        var line =
                jsonbObject(
                                jsonEntry("id", i.PURCHASE_ORDER_ITEM_ID),
                                        jsonEntry("materialId", i.RAW_MATERIAL_ID),
                                jsonEntry("sourceTermId", i.SUPPLIER_MATERIAL_TERM_ID),
                                        jsonEntry("buyUnit", i.PURCHASE_UNIT),
                                jsonEntry("buyQuantity", i.PURCHASE_QUANTITY),
                                        jsonEntry("buyUnitPrice", i.PURCHASE_UNIT_PRICE),
                                jsonEntry("baseUnit", i.BASE_UNIT),
                                        jsonEntry("baseQuantity", i.QUANTITY),
                                jsonEntry("baseUnitsPerBuyUnit", i.BASE_QUANTITY_PER_PURCHASE_UNIT),
                                        jsonEntry("baseUnitPrice", i.UNIT_PRICE),
                                jsonEntry("expectedDeliveryDate", i.EXPECTED_DELIVERY_DATE),
                                        jsonEntry("lineAmountKrw", i.LINE_AMOUNT))
                        .nullOnNull();
        var lines =
                coalesce(
                        field(
                                select(
                                                jsonbArrayAgg(line)
                                                        .orderBy(
                                                                i.RAW_MATERIAL_ID,
                                                                i.SUPPLIER_MATERIAL_TERM_ID,
                                                                i.EXPECTED_DELIVERY_DATE,
                                                                i.PURCHASE_ORDER_ITEM_ID))
                                        .from(i)
                                        .where(i.PURCHASE_ORDER_ID.eq(p.PURCHASE_ORDER_ID))),
                        val(EMPTY_ARRAY));
        var order =
                jsonbObject(
                                jsonEntry("id", p.PURCHASE_ORDER_ID),
                                        jsonEntry("supplierId", p.SUPPLIER_ID),
                                jsonEntry("warehouseId", p.WAREHOUSE_ID),
                                        jsonEntry("createdBy", p.CREATED_BY),
                                jsonEntry("orderDate", p.ORDER_DATE),
                                        jsonEntry("expectedDeliveryDate", p.EXPECTED_DELIVERY_DATE),
                                jsonEntry("ordered", field(p.STATUS.ne(PurchaseOrderStatus.DRAFT))),
                                        jsonEntry("lines", lines))
                        .nullOnNull();
        var document =
                jsonbObject(
                        jsonEntry(
                                "orders",
                                coalesce(
                                        jsonbArrayAgg(order)
                                                .orderBy(p.SUPPLIER_ID, p.PURCHASE_ORDER_ID),
                                        val(EMPTY_ARRAY))));
        return dsl.select(document)
                .from(p)
                .where(p.PURCHASE_APPLICATION_ID.eq(applicationId))
                .fetchSingle(r -> json.readTree(r.value1().data()));
    }

    public Optional<State> state(long caseId, long workId) {
        var w = WORK_ITEMS.as("work");
        var p = REPLENISHMENT_PLANS.as("plan");
        var latest = REPLENISHMENT_PLANS.as("latest");
        var origin = WORK_ITEMS.as("origin");
        var current =
                field(
                        p.REPLENISHMENT_PLAN_ID
                                .eq(
                                        select(max(latest.REPLENISHMENT_PLAN_ID))
                                                .from(latest)
                                                .where(latest.CASE_ID.eq(w.CASE_ID)))
                                .and(origin.LATEST_PLANNING_PLAN_ID.eq(p.REPLENISHMENT_PLAN_ID))
                                .and(origin.LATEST_PLANNING_OUTCOME.eq("READY"))
                                .and(origin.STATUS.eq(WorkItemStatus.DONE)));
        return dsl.select(w.PROCUREMENT_OUTCOME, p.RESULT, p.REPLENISHMENT_PLAN_ID, current)
                .from(w)
                .join(p)
                .on(p.REPLENISHMENT_PLAN_ID.eq(w.PROCUREMENT_PLAN_ID).and(p.CASE_ID.eq(w.CASE_ID)))
                .join(origin)
                .on(
                        origin.WORK_ITEM_ID
                                .eq(p.CREATED_BY_WORK_ITEM_ID)
                                .and(origin.CASE_ID.eq(p.CASE_ID)))
                .where(w.WORK_ITEM_ID.eq(workId).and(w.CASE_ID.eq(caseId)))
                .fetchOptional(
                        r ->
                                new State(
                                        r.value1(),
                                        json.readTree(r.value2().data()),
                                        r.value3(),
                                        Boolean.TRUE.equals(r.value4())));
    }

    public Optional<Application> application(long caseId, long workId, long planId) {
        var app = PURCHASE_APPLICATIONS.as("app");
        var g = GOVERNANCE_ACTIONS.as("approval");
        var d = GOVERNANCE_DECISIONS.as("decision");
        return dsl.select(
                        app.PURCHASE_APPLICATION_ID,
                        app.APPLIED_BY_USER_ID,
                        app.VERIFICATION_RECEIPT,
                        g.PAYLOAD,
                        g.PROPOSAL_HASH)
                .from(app)
                .join(g)
                .on(g.GOVERNANCE_ACTION_ID.eq(app.GOVERNANCE_ACTION_ID))
                .join(d)
                .on(d.GOVERNANCE_DECISION_ID.eq(app.GOVERNANCE_DECISION_ID))
                .where(
                        app.CASE_ID
                                .eq(caseId)
                                .and(app.WORK_ITEM_ID.eq(workId))
                                .and(app.REPLENISHMENT_PLAN_ID.eq(planId)))
                .and(g.STATUS.eq(GovernanceActionStatus.APPROVED))
                .and(g.REQUIRED_ROLE.eq(UserRole.MANAGER))
                .and(d.DECISION.eq(GovernanceDecisionType.APPROVE))
                .and(d.IS_FINAL.isTrue())
                .and(d.DECIDED_BY.eq(app.APPLIED_BY_USER_ID))
                .fetchOptional(
                        r ->
                                new Application(
                                        r.value1(),
                                        r.value2(),
                                        json.readTree(r.value3().data()),
                                        json.readTree(r.value4().data()),
                                        r.value5()));
    }

    public record State(String outcome, JsonNode result, long planId, boolean current) {}

    public record Application(
            long id, long user, JsonNode receipt, JsonNode payload, String hash) {}
}
