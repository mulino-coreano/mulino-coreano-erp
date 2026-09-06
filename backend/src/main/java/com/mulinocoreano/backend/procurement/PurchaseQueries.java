package com.mulinocoreano.backend.procurement;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_ACTIONS;
import static com.mulinocoreano.backend.generated.Tables.GOVERNANCE_DECISIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_APPLICATIONS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDERS;
import static com.mulinocoreano.backend.generated.Tables.PURCHASE_ORDER_ITEMS;
import static com.mulinocoreano.backend.generated.Tables.RAW_MATERIALS;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;
import static com.mulinocoreano.backend.generated.Tables.SUPPLIERS;
import static com.mulinocoreano.backend.generated.Tables.USERS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.jsonEntry;
import static org.jooq.impl.DSL.jsonbArrayAgg;
import static org.jooq.impl.DSL.jsonbObject;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.generated.tables.PurchaseOrders;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

/** Read projections use generated columns while retaining the existing JSON API contract. */
@Repository
public class PurchaseQueries {
    private static final JSONB EMPTY_ARRAY = JSONB.valueOf("[]");
    private final DSLContext dsl;
    private final CanonicalJson json;

    public PurchaseQueries(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public JsonNode approval(long id) {
        var g = GOVERNANCE_ACTIONS.as("g");
        var c = CASES.as("c");
        var w = WORK_ITEMS.as("w");
        var p = REPLENISHMENT_PLANS.as("p");
        var requester = USERS.as("requester");
        var agent = AGENTS.as("agent");
        var app = PURCHASE_APPLICATIONS.as("app");

        var document =
                jsonbObject(
                                jsonEntry("id", g.GOVERNANCE_ACTION_ID),
                                jsonEntry(
                                        "ref",
                                        concat(
                                                val("APPROVAL-"),
                                                g.GOVERNANCE_ACTION_ID.cast(String.class))),
                                jsonEntry("caseRef", c.CASE_REF),
                                jsonEntry("workItemRef", w.WORK_ITEM_REF),
                                jsonEntry("planRef", p.PLAN_REF),
                                jsonEntry("version", g.PROPOSAL_VERSION),
                                jsonEntry("proposalHash", g.PROPOSAL_HASH),
                                jsonEntry("status", g.STATUS),
                                jsonEntry("requiredRole", g.REQUIRED_ROLE),
                                jsonEntry("requestedBy", g.REQUESTED_BY),
                                jsonEntry("requestedByName", requester.NAME),
                                jsonEntry("proposedBy", agent.AGENT_KEY),
                                jsonEntry("requestedAt", g.REQUESTED_AT),
                                jsonEntry("expiresAt", g.EXPIRES_AT),
                                jsonEntry("proposal", g.PAYLOAD),
                                jsonEntry("decision", latestDecision(g.GOVERNANCE_ACTION_ID)),
                                jsonEntry("applicationId", app.PURCHASE_APPLICATION_ID),
                                jsonEntry(
                                        "purchaseOrderIds",
                                        purchaseOrderIds(app.PURCHASE_APPLICATION_ID)))
                        .nullOnNull();

        return dsl.select(document)
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
                .join(requester)
                .on(requester.USER_ID.eq(g.REQUESTED_BY))
                .join(agent)
                .on(agent.AGENT_ID.eq(g.PROPOSED_BY_AGENT_ID))
                .leftJoin(app)
                .on(app.GOVERNANCE_ACTION_ID.eq(g.GOVERNANCE_ACTION_ID))
                .where(g.GOVERNANCE_ACTION_ID.eq(id))
                .fetchOptional(record -> json.readTree(record.value1().data()))
                .orElseThrow(() -> notFound("Purchase approval is not available"));
    }

    public JsonNode order(long id) {
        var po = PURCHASE_ORDERS.as("po");
        var supplier = SUPPLIERS.as("supplier");
        var app = PURCHASE_APPLICATIONS.as("app");
        var c = CASES.as("c");
        var p = REPLENISHMENT_PLANS.as("p");

        var document =
                jsonbObject(
                                jsonEntry("id", po.PURCHASE_ORDER_ID),
                                jsonEntry("supplierId", po.SUPPLIER_ID),
                                jsonEntry("supplierName", supplier.NAME),
                                jsonEntry("warehouseId", po.WAREHOUSE_ID),
                                jsonEntry("createdBy", po.CREATED_BY),
                                jsonEntry("orderDate", po.ORDER_DATE),
                                jsonEntry("expectedDeliveryDate", po.EXPECTED_DELIVERY_DATE),
                                jsonEntry("status", po.STATUS),
                                jsonEntry("taxInvoiceNumber", po.TAX_INVOICE_NUMBER),
                                jsonEntry("taxInvoiceDate", po.TAX_INVOICE_DATE),
                                jsonEntry("applicationId", app.PURCHASE_APPLICATION_ID),
                                jsonEntry("approvalId", app.GOVERNANCE_ACTION_ID),
                                jsonEntry("caseRef", c.CASE_REF),
                                jsonEntry("planRef", p.PLAN_REF),
                                jsonEntry("items", orderLines(po)))
                        .nullOnNull();

        return dsl.select(document)
                .from(po)
                .join(supplier)
                .on(supplier.SUPPLIER_ID.eq(po.SUPPLIER_ID))
                .leftJoin(app)
                .on(app.PURCHASE_APPLICATION_ID.eq(po.PURCHASE_APPLICATION_ID))
                .leftJoin(c)
                .on(c.CASE_ID.eq(app.CASE_ID))
                .leftJoin(p)
                .on(p.REPLENISHMENT_PLAN_ID.eq(app.REPLENISHMENT_PLAN_ID))
                .where(po.PURCHASE_ORDER_ID.eq(id))
                .fetchOptional(record -> json.readTree(record.value1().data()))
                .orElseThrow(() -> notFound("Purchase order is not available"));
    }

    private Field<JSONB> latestDecision(Field<Long> approvalId) {
        var d = GOVERNANCE_DECISIONS.as("decision");
        var document =
                jsonbObject(
                                jsonEntry("id", d.GOVERNANCE_DECISION_ID),
                                jsonEntry("decision", d.DECISION),
                                jsonEntry("decidedBy", d.DECIDED_BY),
                                jsonEntry("reason", d.REASON),
                                jsonEntry("decidedAt", d.DECIDED_AT))
                        .nullOnNull();
        return field(
                select(document)
                        .from(d)
                        .where(d.GOVERNANCE_ACTION_ID.eq(approvalId).and(d.IS_FINAL.isTrue()))
                        .orderBy(d.GOVERNANCE_DECISION_ID.desc())
                        .limit(1));
    }

    private Field<JSONB> purchaseOrderIds(Field<Long> applicationId) {
        var po = PURCHASE_ORDERS.as("linked_po");
        return coalesce(
                field(
                        select(jsonbArrayAgg(po.PURCHASE_ORDER_ID).orderBy(po.PURCHASE_ORDER_ID))
                                .from(po)
                                .where(po.PURCHASE_APPLICATION_ID.eq(applicationId))),
                val(EMPTY_ARRAY));
    }

    private Field<JSONB> orderLines(PurchaseOrders order) {
        var i = PURCHASE_ORDER_ITEMS.as("item");
        var material = RAW_MATERIALS.as("material");
        var line =
                jsonbObject(
                                jsonEntry("id", i.PURCHASE_ORDER_ITEM_ID),
                                jsonEntry("materialId", i.RAW_MATERIAL_ID),
                                jsonEntry("materialName", material.NAME),
                                jsonEntry("baseQuantity", i.QUANTITY),
                                jsonEntry("receivedBaseQuantity", i.RECEIVED_QUANTITY),
                                jsonEntry("baseUnitPrice", i.UNIT_PRICE),
                                jsonEntry("baseUnit", coalesce(i.BASE_UNIT, material.UNIT)),
                                jsonEntry("buyQuantity", i.PURCHASE_QUANTITY),
                                jsonEntry("buyUnit", i.PURCHASE_UNIT),
                                jsonEntry("buyUnitPrice", i.PURCHASE_UNIT_PRICE),
                                jsonEntry("baseUnitsPerBuyUnit", i.BASE_QUANTITY_PER_PURCHASE_UNIT),
                                jsonEntry("lineAmountKrw", i.LINE_AMOUNT),
                                jsonEntry(
                                        "expectedDeliveryDate",
                                        coalesce(
                                                i.EXPECTED_DELIVERY_DATE,
                                                order.EXPECTED_DELIVERY_DATE)),
                                jsonEntry("sourceTermId", i.SUPPLIER_MATERIAL_TERM_ID))
                        .nullOnNull();
        return coalesce(
                field(
                        select(jsonbArrayAgg(line).orderBy(i.PURCHASE_ORDER_ITEM_ID))
                                .from(i)
                                .join(material)
                                .on(material.RAW_MATERIAL_ID.eq(i.RAW_MATERIAL_ID))
                                .where(i.PURCHASE_ORDER_ID.eq(order.PURCHASE_ORDER_ID))),
                val(EMPTY_ARRAY));
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
