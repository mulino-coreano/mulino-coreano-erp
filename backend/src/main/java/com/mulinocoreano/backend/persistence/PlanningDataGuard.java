package com.mulinocoreano.backend.persistence;

import static com.mulinocoreano.backend.generated.Tables.PLANNING_DATA_GUARD;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Serializes planning inputs, plan revisions and purchasing decisions in a fresh snapshot.
 * Call {@link #lock()} before any other statement of the REPEATABLE READ transaction.
 */
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
        var guard = PLANNING_DATA_GUARD;
        // LOCK TABLE does not freeze a REPEATABLE READ snapshot. Taken first, it queues this
        // transaction behind other planning transactions and in-flight planning-data writes
        // (their triggers need ROW EXCLUSIVE), so the snapshot starts after they commit and
        // no planning-data write can commit until this transaction ends.
        dsl.execute("LOCK TABLE {0} IN SHARE ROW EXCLUSIVE MODE", guard);
        // A caller whose snapshot was already frozen still gets SQLSTATE 40001 here when
        // planning data changed since then, and retries the whole operation with a fresh one.
        dsl.update(guard)
                .set(guard.REVISION, guard.REVISION.add(1))
                .where(guard.GUARD_ID.eq((short) 1))
                .returningResult(guard.GUARD_ID)
                .fetchSingle();
    }
}
