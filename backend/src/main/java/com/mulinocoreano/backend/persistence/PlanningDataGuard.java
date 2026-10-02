package com.mulinocoreano.backend.persistence;

import static com.mulinocoreano.backend.generated.Tables.PLANNING_DATA_GUARD;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes planning inputs, plan revisions and purchasing decisions in a fresh snapshot. */
@Repository
public class PlanningDataGuard {
    private final DSLContext dsl;

    public PlanningDataGuard(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Planning coordination requires a caller transaction");
        }
        // A row lock alone keeps an old REPEATABLE READ snapshot after waiting.
        // Updating its revision makes that transaction fail with SQLSTATE 40001,
        // so the caller retries the whole operation with a fresh snapshot.
        var guard = PLANNING_DATA_GUARD;
        dsl.update(guard)
                .set(guard.REVISION, guard.REVISION.add(1))
                .where(guard.GUARD_ID.eq((short) 1))
                .returningResult(guard.GUARD_ID)
                .fetchSingle();
    }
}
