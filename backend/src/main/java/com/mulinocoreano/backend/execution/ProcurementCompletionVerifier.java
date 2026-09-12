package com.mulinocoreano.backend.execution;

/** Deterministic procurement evidence check, invoked inside the current Run transaction.
 * Implementations must verify persisted business results, never the model's completion claim. */
public interface ProcurementCompletionVerifier {
    boolean verified(long caseId, long workItemId);
}
