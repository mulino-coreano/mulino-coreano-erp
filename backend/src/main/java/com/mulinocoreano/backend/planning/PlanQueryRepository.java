package com.mulinocoreano.backend.planning;

import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.REPLENISHMENT_PLANS;

import static org.jooq.impl.DSL.selectOne;

import com.mulinocoreano.backend.interfacepackage.AttentionDto;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/** Immutable plan readback, shared by human and scoped agent query paths. */
@Repository
public class PlanQueryRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;
    private final ObjectMapper mapper;

    public PlanQueryRepository(DSLContext dsl, CanonicalJson json, ObjectMapper mapper) {
        this.dsl = dsl;
        this.json = json;
        this.mapper = mapper;
    }

    public boolean belongsToCase(String ref, long caseId) {
        return dsl.fetchExists(
                selectOne()
                        .from(REPLENISHMENT_PLANS)
                        .where(
                                REPLENISHMENT_PLANS
                                        .PLAN_REF
                                        .eq(ref)
                                        .and(REPLENISHMENT_PLANS.CASE_ID.eq(caseId))));
    }

    public Optional<PlanDto> find(String ref) {
        var p = REPLENISHMENT_PLANS;
        return dsl.select(
                        p.PLAN_REF,
                        CASES.CASE_REF,
                        p.VERSION,
                        p.WAREHOUSE_ID,
                        p.AS_OF,
                        p.HORIZON_DAYS,
                        p.TARGET_DATE,
                        p.SOURCE_SNAPSHOT,
                        p.RESULT,
                        p.SOURCE_HASH,
                        p.PLAN_HASH)
                .from(p)
                .join(CASES)
                .on(CASES.CASE_ID.eq(p.CASE_ID))
                .where(p.PLAN_REF.eq(ref))
                .fetchOptional(
                        row -> {
                            var result = json.readTree(row.get(p.RESULT).data());
                            var attention =
                                    result.hasNonNull("attention")
                                            ? mapper.treeToValue(
                                                    result.get("attention"), AttentionDto.class)
                                            : null;
                            return new PlanDto(
                                    row.get(p.PLAN_REF),
                                    row.get(CASES.CASE_REF),
                                    row.get(p.VERSION),
                                    row.get(p.WAREHOUSE_ID),
                                    row.get(p.AS_OF).toInstant(),
                                    row.get(p.HORIZON_DAYS),
                                    row.get(p.TARGET_DATE),
                                    json.readTree(row.get(p.SOURCE_SNAPSHOT).data()),
                                    result,
                                    row.get(p.SOURCE_HASH),
                                    row.get(p.PLAN_HASH),
                                    attention);
                        });
    }
}
