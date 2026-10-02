package com.mulinocoreano.backend.execution;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.RUNS;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.val;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.types.DayToSecond;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/** All execution writes acquire locks in Work Item -> Run -> Case -> Agent order. */
@Repository
public class RunLeaseRepository {
    private static final Field<OffsetDateTime> NOW =
            function(name("clock_timestamp"), OffsetDateTime.class);
    private static final DayToSecond MAX_RUNTIME = DayToSecond.valueOf(Duration.ofSeconds(600));
    private final DSLContext dsl;

    public RunLeaseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Long> findByCapabilityHash(String hash) {
        return dsl.select(RUNS.RUN_ID)
                .from(RUNS)
                .where(RUNS.CAPABILITY_TOKEN_HASH.eq(hash))
                .fetchOptional(RUNS.RUN_ID);
    }

    public RunRow lockByRef(String ref) {
        requireTransaction();
        long id =
                dsl.select(RUNS.RUN_ID)
                        .from(RUNS)
                        .where(RUNS.RUN_REF.eq(ref))
                        .fetchOptional(RUNS.RUN_ID)
                        .orElseThrow(RunLeaseRepository::stale);
        return lock(id);
    }

    public RunRow lock(long id) {
        requireTransaction();
        long workId =
                dsl.select(RUNS.WORK_ITEM_ID)
                        .from(RUNS)
                        .where(RUNS.RUN_ID.eq(id).and(RUNS.WORK_ITEM_ID.isNotNull()))
                        .fetchOptional(RUNS.WORK_ITEM_ID)
                        .orElseThrow(RunLeaseRepository::stale);
        dsl.select(WORK_ITEMS.WORK_ITEM_ID)
                .from(WORK_ITEMS)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(workId))
                .forUpdate()
                .fetchSingle();
        dsl.select(RUNS.RUN_ID).from(RUNS).where(RUNS.RUN_ID.eq(id)).forUpdate().fetchSingle();
        dsl.select(CASES.CASE_ID)
                .from(CASES)
                .join(RUNS)
                .on(RUNS.CASE_ID.eq(CASES.CASE_ID))
                .where(RUNS.RUN_ID.eq(id))
                .forShare()
                .of(CASES)
                .fetchSingle();
        dsl.select(AGENTS.AGENT_ID)
                .from(AGENTS)
                .join(RUNS)
                .on(RUNS.AGENT_ID.eq(AGENTS.AGENT_ID))
                .where(RUNS.RUN_ID.eq(id))
                .forShare()
                .of(AGENTS)
                .fetchSingle();
        return currentState(id);
    }

    private RunRow currentState(long id) {
        var r = RUNS.as("run");
        var w = WORK_ITEMS.as("work");
        var c = CASES.as("case");
        var a = AGENTS.as("agent");
        var assigned =
                field(
                        w.CASE_ID
                                .eq(r.CASE_ID)
                                .and(w.ASSIGNED_AGENT_ID.eq(r.AGENT_ID))
                                .and(w.ASSIGNED_USER_ID.isNull()));
        var live =
                field(r.LEASE_EXPIRES_AT.gt(NOW).and(r.CLAIMED_AT.add(val(MAX_RUNTIME)).gt(NOW)));
        return dsl.select(
                        r.RUN_ID,
                        r.RUN_REF,
                        r.CASE_ID,
                        r.WORK_ITEM_ID,
                        r.AGENT_ID,
                        r.RUNTIME,
                        r.STATUS,
                        r.LEASE_OWNER,
                        r.LEASE_TOKEN_HASH,
                        r.CAPABILITY_TOKEN_HASH,
                        r.LEASE_EXPIRES_AT,
                        r.CLAIMED_AT,
                        r.ATTEMPT,
                        r.OUTCOME,
                        c.CASE_REF,
                        c.STATUS,
                        w.WORK_ITEM_REF,
                        w.STATUS,
                        a.AGENT_KEY,
                        a.IS_ACTIVE,
                        assigned,
                        live)
                .from(r)
                .join(w)
                .on(w.WORK_ITEM_ID.eq(r.WORK_ITEM_ID))
                .join(c)
                .on(c.CASE_ID.eq(r.CASE_ID))
                .join(a)
                .on(a.AGENT_ID.eq(r.AGENT_ID))
                .where(r.RUN_ID.eq(id))
                .fetchSingle(
                        row ->
                                new RunRow(
                                        row.get(r.RUN_ID),
                                        row.get(r.RUN_REF),
                                        row.get(r.CASE_ID),
                                        row.get(r.WORK_ITEM_ID),
                                        row.get(r.AGENT_ID),
                                        row.get(r.RUNTIME),
                                        row.get(r.STATUS).getLiteral(),
                                        row.get(r.LEASE_OWNER),
                                        row.get(r.LEASE_TOKEN_HASH),
                                        row.get(r.CAPABILITY_TOKEN_HASH),
                                        instant(row.get(r.LEASE_EXPIRES_AT)),
                                        instant(row.get(r.CLAIMED_AT)),
                                        row.get(r.ATTEMPT),
                                        row.get(r.OUTCOME),
                                        row.get(c.CASE_REF),
                                        row.get(c.STATUS).getLiteral(),
                                        row.get(w.WORK_ITEM_REF),
                                        row.get(w.STATUS).getLiteral(),
                                        row.get(a.AGENT_KEY),
                                        Boolean.TRUE.equals(row.get(a.IS_ACTIVE)),
                                        Boolean.TRUE.equals(row.get(assigned)),
                                        Boolean.TRUE.equals(row.get(live))));
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public void requireLive(RunRow row) {
        if (!"RUNNING".equals(row.status())
                || !row.leaseValid()
                || !row.currentAssignment()
                || !"IN_PROGRESS".equals(row.workStatus())) throw stale();
    }

    public static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run authorization requires an existing transaction");
    }

    public static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "STALE_LEASE");
    }

    public record RunRow(
            long id,
            String ref,
            long caseId,
            long workId,
            long agentId,
            String runtime,
            String status,
            String owner,
            String leaseHash,
            String capabilityHash,
            Instant expiresAt,
            Instant claimedAt,
            int attempt,
            String outcome,
            String caseRef,
            String caseStatus,
            String workRef,
            String workStatus,
            String agentKey,
            boolean active,
            boolean assigned,
            boolean leaseValid) {
        public boolean currentAssignment() {
            return assigned
                    && active
                    && !java.util.Set.of("RESOLVED", "CLOSED", "CANCELLED").contains(caseStatus);
        }

        public boolean terminal() {
            return java.util.Set.of("COMPLETED", "FAILED", "ABORTED").contains(status);
        }
    }
}
