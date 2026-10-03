package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.planning.CanonicalJson;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** Completion checks actual inserted ERP rows, not an agent's summary or metadata. */
@Service
public class PurchaseVerificationService {
    private final PurchaseEvidenceRepository evidence;
    private final CanonicalJson json;
    private final ObjectMapper mapper;

    public PurchaseVerificationService(
            PurchaseEvidenceRepository evidence, CanonicalJson json, ObjectMapper mapper) {
        this.evidence = evidence;
        this.json = json;
        this.mapper = mapper;
    }

    public JsonNode manifest(long applicationId) {
        return evidence.manifest(applicationId);
    }

    public boolean matches(PurchaseBundle bundle, long approver, JsonNode actual) {
        var orders = new ArrayList<Map<String, Object>>();
        for (var order : bundle.orders()) {
            var lines = new ArrayList<Map<String, Object>>();
            for (var line : order.lines()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("materialId", line.materialId());
                item.put("sourceTermId", line.sourceTermId());
                item.put("buyUnit", line.buyUnit());
                item.put("buyQuantity", line.buyQuantity());
                item.put("buyUnitPrice", line.buyUnitPrice());
                item.put("baseUnit", line.baseUnit());
                item.put("baseQuantity", line.baseQuantity());
                item.put("baseUnitsPerBuyUnit", line.baseUnitsPerBuyUnit());
                item.put("baseUnitPrice", line.baseUnitPrice());
                item.put("expectedDeliveryDate", line.expectedDeliveryDate());
                item.put("lineAmountKrw", line.lineAmountKrw());
                lines.add(item);
            }
            orders.add(
                    Map.of(
                            "supplierId",
                            order.supplierId(),
                            "warehouseId",
                            bundle.warehouseId(),
                            "createdBy",
                            approver,
                            "orderDate",
                            bundle.asOf(),
                            "expectedDeliveryDate",
                            order.expectedDeliveryDate(),
                            "ordered",
                            true,
                            "lines",
                            lines));
        }
        JsonNode withoutIds = actual.deepCopy();
        withoutIds
                .path("orders")
                .forEach(
                        order -> {
                            ((ObjectNode) order).remove("id");
                            order.path("lines").forEach(line -> ((ObjectNode) line).remove("id"));
                        });
        return json.sha256(Map.of("orders", orders)).equals(json.sha256(withoutIds));
    }

    public boolean verified(long caseId, long workItemId) {
        var state = evidence.state(caseId, workItemId);
        if (state.isEmpty()) return false;
        if ("NO_PURCHASE_REQUIRED".equals(state.get().outcome()))
            return state.get().current()
                    && "READY".equals(state.get().result().path("status").asText())
                    && state.get().result().path("purchases").isArray()
                    && state.get().result().path("purchases").isEmpty();
        if (!"APPLIED".equals(state.get().outcome())) return false;
        var application = evidence.application(caseId, workItemId, state.get().planId());
        if (application.isEmpty()) return false;
        var app = application.get();
        var actual = manifest(app.id());
        if (!json.sha256(app.payload()).equals(app.hash())
                || !json.sha256(actual).equals(app.receipt().path("manifestHash").asText()))
            return false;
        return matches(mapper.treeToValue(app.payload(), PurchaseBundle.class), app.user(), actual)
                && !actual.path("orders").isEmpty();
    }
}
