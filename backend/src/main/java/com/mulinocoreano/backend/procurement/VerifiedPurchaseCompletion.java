package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.execution.ProcurementCompletionVerifier;

import org.springframework.stereotype.Component;

@Component
public class VerifiedPurchaseCompletion implements ProcurementCompletionVerifier {
    private final PurchaseVerificationService verification;

    public VerifiedPurchaseCompletion(PurchaseVerificationService verification) {
        this.verification = verification;
    }

    @Override
    public boolean verified(long caseId, long workItemId) {
        return verification.verified(caseId, workItemId);
    }
}
