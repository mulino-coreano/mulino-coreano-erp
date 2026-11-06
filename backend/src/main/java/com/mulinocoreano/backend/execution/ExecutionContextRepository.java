package com.mulinocoreano.backend.execution;

import static com.mulinocoreano.backend.generated.Tables.*;

import static org.jooq.impl.DSL.*;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Reads current execution facts without changing stored Run or plan evidence. */
@Repository
public class ExecutionContextRepository {
    private final DSLContext dsl;

    public ExecutionContextRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public String caseMetadata(long caseId) {
        return dsl.select(coalesce(CASES.METADATA, JSONB.valueOf("{}")))
                .from(CASES)
                .where(CASES.CASE_ID.eq(caseId))
                .fetchSingle()
                .value1()
                .data();
    }

    public Optional<PriorPlan> latestPlan(long caseId) {
        var p = REPLENISHMENT_PLANS;
        return dsl.select(
                        p.PLAN_REF,
                        p.VERSION,
                        p.WAREHOUSE_ID,
                        p.HORIZON_DAYS,
                        p.SOURCE_SNAPSHOT,
                        p.RESULT)
                .from(p)
                .where(p.CASE_ID.eq(caseId))
                .orderBy(p.VERSION.desc())
                .limit(1)
                .fetchOptional(
                        r ->
                                new PriorPlan(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5().data(),
                                        r.value6().data()));
    }

    public record PriorPlan(
            String ref, int version, long warehouse, int horizon, String source, String result) {}
}
