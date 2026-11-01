package com.mulinocoreano.backend.execution;

import org.springframework.stereotype.Component;

/**
 * Agent Idempotency-Key receipts belong to one execution: a Run and its lease-expiry retries.
 * A retry repeats the attempt whose response may have been lost, so it replays the receipt. A Run
 * resumed after WAITING has no retry origin, so it starts a new execution and runs again against
 * current ERP facts.
 */
@Component
public class AgentReceiptScope {
    private final RunLeaseRepository leases;

    public AgentReceiptScope(RunLeaseRepository leases) {
        this.leases = leases;
    }

    public String namespace(String operation, RunCapabilityAccess.RunScope scope) {
        return operation + ":run:" + leases.executionRoot(scope.runId());
    }
}
