package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.interfacepackage.ContextSnapshotService;
import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconstructs current facts without replacing the original Run or plan audit snapshots. */
@Component
public class ExecutionContextBuilder {
    private final ContextSnapshotService contexts;
    private final PlanningSnapshotRepository snapshots;
    private final ExecutionContextRepository repository;
    private final CanonicalJson json;
    private final Clock clock;

    public ExecutionContextBuilder(
            ContextSnapshotService contexts,
            PlanningSnapshotRepository snapshots,
            ExecutionContextRepository repository,
            CanonicalJson json,
            @Qualifier("planningClock") Clock clock) {
        this.contexts = contexts;
        this.snapshots = snapshots;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> build(String caseRef, long caseId) {
        var context = new LinkedHashMap<String, Object>(contexts.build(caseRef));
        context.put("caseRef", caseRef);
        context.put("caseMetadata", json.readTree(repository.caseMetadata(caseId)));
        context.put(
                "purchasing",
                json.readTree("[" + String.join(",", repository.recentPurchasing(caseId)) + "]"));
        repository
                .latestPlan(caseId)
                .ifPresent(
                        plan -> {
                            var source = json.readTree(plan.source());
                            var productIds =
                                    java.util.stream.StreamSupport.stream(
                                                    source.path("products").spliterator(), false)
                                            .map(
                                                    product ->
                                                            product.path("item")
                                                                    .path("id")
                                                                    .asLong())
                                            .distinct()
                                            .sorted()
                                            .toList();
                            context.put(
                                    "latestPlan",
                                    Map.of(
                                            "ref",
                                            plan.ref(),
                                            "version",
                                            plan.version(),
                                            "sourceSnapshot",
                                            source,
                                            "result",
                                            json.readTree(plan.result())));
                            context.put(
                                    "currentBusinessFacts",
                                    snapshots.load(
                                            plan.warehouse(),
                                            productIds,
                                            LocalDate.now(clock),
                                            plan.horizon()));
                        });
        return context;
    }
}
