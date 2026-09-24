package com.mulinocoreano.backend.planning;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReplenishmentCalculator {
    private final ForecastService forecast;
    private final BomPlanner bom;
    private final SupplierSelectionService suppliers;
    public ReplenishmentCalculator(ForecastService forecast, BomPlanner bom, SupplierSelectionService suppliers) {
        this.forecast = forecast; this.bom = bom; this.suppliers = suppliers;
    }
    public record PurchaseRequirement(BomPlanner.Item material, LocalDate firstNeedDate,
                                      BigDecimal netBaseQuantity, SupplierSelectionService.SelectionResult selection) {}
    public record Issue(String code, String resourceRef) {}
    public record Calculation(String status, Map<Long, ForecastService.ForecastResult> forecasts,
                              BomPlanner.Result requirements, List<PurchaseRequirement> purchases,
                              BigDecimal totalAmount, List<Issue> issues) {}

    public Calculation calculate(PlanningSnapshotRepository.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "SNAPSHOT_REQUIRED");
        if (snapshot.products() == null || snapshot.products().isEmpty()) throw new IllegalArgumentException("PRODUCTS_REQUIRED");
        Map<Long, ForecastService.ForecastResult> forecasts = new TreeMap<>();
        List<BomPlanner.Demand> demand = new ArrayList<>();
        for (var product : snapshot.products()) {
            if (forecasts.containsKey(product.item().id())) throw new IllegalArgumentException("DUPLICATE_PRODUCT");
            var result = forecast.forecast(snapshot.asOf(), snapshot.historyStartDate(), snapshot.horizonDays(), snapshot.safetyDays(),
                    product.history(), product.openOrders());
            forecasts.put(product.item().id(), result);
            for (var day : result.dailyDemand()) demand.add(new BomPlanner.Demand(product.item(), day.date(), day.effectiveDemand()));
            // Safety is a target stock at the end, not additional demand repeated on each day.
            demand.add(new BomPlanner.Demand(product.item(), snapshot.asOf().plusDays(snapshot.horizonDays() - 1L), result.safetyQuantity()));
        }
        var requirements = bom.plan(snapshot.asOf(), demand, snapshot.boms(), snapshot.supply());
        Map<Long, List<BomPlanner.MaterialRequirement>> shortages = new TreeMap<>();
        for (var material : requirements.materials()) {
            if (material.netQuantity().signum() > 0) shortages.computeIfAbsent(material.material().id(), ignored -> new ArrayList<>()).add(material);
        }
        List<PurchaseRequirement> purchases = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : shortages.entrySet()) {
            var material = entry.getValue().getFirst().material();
            var firstNeed = entry.getValue().stream().map(BomPlanner.MaterialRequirement::needDate).min(LocalDate::compareTo).orElseThrow();
            var net = entry.getValue().stream().map(BomPlanner.MaterialRequirement::netQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            // One bulk order covers the horizon's remaining requirement by its earliest shortage.
            // Apply MOQ once after dated inventory/confirmed-receipt allocation, not per daily bucket.
            var selection = suppliers.select(snapshot.asOf(), firstNeed, net, material.unit(),
                    snapshot.supplierTerms().getOrDefault(material.id(), List.of()), snapshot.certificates());
            purchases.add(new PurchaseRequirement(material, firstNeed, net, selection));
            if (selection.chosen() == null) issues.add(new Issue("NO_ELIGIBLE_SUPPLIER", "raw_materials:" + material.id()));
            else total = total.add(selection.chosen().totalAmount());
        }
        return new Calculation(issues.isEmpty() ? "READY" : "NEEDS_ATTENTION", Collections.unmodifiableMap(forecasts),
                requirements, List.copyOf(purchases), issues.isEmpty() ? total : null, List.copyOf(issues));
    }
}
