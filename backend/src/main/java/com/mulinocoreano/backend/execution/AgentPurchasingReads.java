package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.planning.BomPlanner;
import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;
import com.mulinocoreano.backend.procurement.PurchaseQueries;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Case membership comes from immutable plan evidence or an applied Case purchase. */
@Service
public class AgentPurchasingReads {
    private final ExecutionContextRepository contexts;
    private final PlanningSnapshotRepository snapshots;
    private final PurchaseQueries purchases;
    private final CanonicalJson json;
    private final Clock clock;

    public AgentPurchasingReads(
            ExecutionContextRepository contexts,
            PlanningSnapshotRepository snapshots,
            PurchaseQueries purchases,
            CanonicalJson json,
            @Qualifier("planningClock") Clock clock) {
        this.contexts = contexts;
        this.snapshots = snapshots;
        this.purchases = purchases;
        this.json = json;
        this.clock = clock;
    }

    public JsonNode order(long caseId, long id) {
        boolean member =
                contexts.hasAppliedPurchase(caseId, id)
                        || contexts.latestPlan(caseId)
                                .map(plan -> hasOrderFact(plan, id))
                                .orElse(false);
        if (!member) throw unavailable("Purchase order");
        return purchases.order(id);
    }

    public Map<String, Object> material(long caseId, String caseRef, long id) {
        var plan =
                contexts.latestPlan(caseId)
                        .filter(p -> hasFact(p, "raw_materials:" + id))
                        .orElseThrow(() -> unavailable("Material"));
        try {
            var source = json.readTree(plan.source());
            var roots =
                    StreamSupport.stream(source.path("products").spliterator(), false)
                            .map(p -> p.path("item").path("id").asLong())
                            .distinct()
                            .sorted()
                            .toList();
            var current =
                    snapshots.load(plan.warehouse(), roots, LocalDate.now(clock), plan.horizon());
            var master =
                    current.sourceFacts().stream()
                            .filter(f -> f.sourceRef().equals("raw_materials:" + id))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "MATERIAL_REMOVED_FROM_CURRENT_BOM"));
            var terms = current.supplierTerms().getOrDefault(id, java.util.List.of());
            var supplierIds = terms.stream().map(t -> t.supplierId()).collect(Collectors.toSet());
            return Map.of(
                    "caseRef",
                    caseRef,
                    "planRef",
                    plan.ref(),
                    "warehouseId",
                    current.warehouseId(),
                    "asOf",
                    current.asOf(),
                    "material",
                    master.values(),
                    "supply",
                    current.supply().stream()
                            .filter(
                                    s ->
                                            s.item().kind() == BomPlanner.Kind.MATERIAL
                                                    && s.item().id() == id)
                            .toList(),
                    "supplierTerms",
                    terms,
                    "certificates",
                    current.certificates().stream()
                            .filter(c -> supplierIds.contains(c.supplierId()))
                            .toList());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Current material facts are inconsistent with planning data");
        }
    }

    private boolean hasOrderFact(ExecutionContextRepository.PriorPlan plan, long id) {
        return StreamSupport.stream(
                        json.readTree(plan.source()).path("sourceFacts").spliterator(), false)
                .anyMatch(
                        f ->
                                ("purchase_orders:" + id).equals(f.path("sourceRef").asText())
                                        || (f.path("sourceRef")
                                                        .asText()
                                                        .startsWith("purchase_order_items:")
                                                && f.path("values")
                                                        .path("purchase_order_id")
                                                        .isIntegralNumber()
                                                && f.path("values")
                                                        .path("purchase_order_id")
                                                        .canConvertToLong()
                                                && f.path("values")
                                                                .path("purchase_order_id")
                                                                .longValue()
                                                        == id));
    }

    private boolean hasFact(ExecutionContextRepository.PriorPlan plan, String ref) {
        return StreamSupport.stream(
                        json.readTree(plan.source()).path("sourceFacts").spliterator(), false)
                .anyMatch(f -> ref.equals(f.path("sourceRef").asText()));
    }

    private static ResponseStatusException unavailable(String resource) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, resource + " is not available in the current Case");
    }
}
