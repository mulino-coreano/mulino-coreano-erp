package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.planning.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Revalidates a proposal against the complete current source while its source guard is held. */
@Component
public class PurchasePlanning {
    private final PurchasePlanRepository plans;
    private final CanonicalJson json;
    private final PlanningSnapshotRepository snapshots;
    private final ReplenishmentCalculator calculator;
    private final PurchaseBundleAssembler assembler;
    private final Clock clock;

    public PurchasePlanning(
            PurchasePlanRepository plans,
            CanonicalJson json,
            PlanningSnapshotRepository snapshots,
            ReplenishmentCalculator calculator,
            PurchaseBundleAssembler assembler,
            @Qualifier("planningClock") Clock clock) {
        this.plans = plans;
        this.json = json;
        this.snapshots = snapshots;
        this.calculator = calculator;
        this.assembler = assembler;
        this.clock = clock;
    }

    public void lockSources() {
        plans.lockSources();
    }

    public PurchasePlan load(String ref) {
        return plans.load(ref);
    }

    public PurchaseBundle currentBundle(PurchasePlan plan) {
        if (!plan.current()
                || Set.of("CLOSED", "RESOLVED", "CANCELLED").contains(plan.caseStatus())
                || !"READY".equals(plan.result().path("status").asText()))
            throw new IllegalArgumentException("PLAN_NOT_CURRENT");
        List<Long> productRows = new ArrayList<>();
        plan.source()
                .path("products")
                .forEach(p -> productRows.add(p.path("item").path("id").asLong()));
        List<Long> products = productRows.stream().distinct().sorted().toList();
        JsonNode scope = plan.metadata().path("replenishment");
        if (!scope.isMissingNode() && !scope.isNull()) {
            List<Long> scoped = new ArrayList<>();
            scope.path("productIds").forEach(p -> scoped.add(p.asLong()));
            if (scope.path("warehouseId").asLong() != plan.warehouse()
                    || !scoped.stream().distinct().sorted().toList().equals(products)
                    || !plan.target().toString().equals(scope.path("targetDate").asText()))
                throw new IllegalArgumentException("PLAN_SCOPE_CHANGED");
        }
        LocalDate today = LocalDate.now(clock);
        long days = ChronoUnit.DAYS.between(today, plan.target()) + 1;
        if (days < 1 || days > 90) throw new IllegalArgumentException("PLAN_DATE_CHANGED");
        var source = snapshots.load(plan.warehouse(), products, today, (int) days);
        if (!json.sha256(source).equals(plan.sourceHash()))
            throw new IllegalArgumentException("PLAN_SOURCE_CHANGED");
        var calculated = calculator.calculate(source);
        if (!json.sha256(calculated).equals(json.sha256(plan.result())))
            throw new IllegalArgumentException("PLAN_RESULT_CHANGED");
        return assembler.assemble(
                plan.ref(), plan.version(), plan.hash(), plan.sourceHash(), source, calculated);
    }
}
