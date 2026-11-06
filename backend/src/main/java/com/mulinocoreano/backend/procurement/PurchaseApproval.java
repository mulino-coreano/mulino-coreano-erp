package com.mulinocoreano.backend.procurement;

/** The immutable approval target plus its current decision state. */
public record PurchaseApproval(
        long id,
        long caseId,
        long workId,
        String planRef,
        int version,
        String hash,
        String status,
        long planId) {
    public boolean matches(PurchaseDecisionRequest request) {
        return version == request.expectedVersion() && hash.equals(request.proposalHash());
    }
}
