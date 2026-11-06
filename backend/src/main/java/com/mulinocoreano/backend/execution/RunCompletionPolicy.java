package com.mulinocoreano.backend.execution;

<<<<<<< HEAD
import com.mulinocoreano.backend.followup.ReplenishmentFollowupRepository;
=======
>>>>>>> origin/main
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Verifies role-specific completion evidence inside the caller's locked execution transaction. */
final class RunCompletionPolicy {
    private final RunExecutionRepository repository;
<<<<<<< HEAD
    private final ReplenishmentFollowupRepository followupRepository;
=======
>>>>>>> origin/main
    private final ObjectProvider<ProcurementCompletionVerifier> procurementCompletion;

    RunCompletionPolicy(
            RunExecutionRepository repository,
<<<<<<< HEAD
            ReplenishmentFollowupRepository followupRepository,
            ObjectProvider<ProcurementCompletionVerifier> procurementCompletion) {
        this.repository = repository;
        this.followupRepository = followupRepository;
=======
            ObjectProvider<ProcurementCompletionVerifier> procurementCompletion) {
        this.repository = repository;
>>>>>>> origin/main
        this.procurementCompletion = procurementCompletion;
    }

    void validate(RunLeaseRepository.RunRow row) {
        if ("SUPPLY_CHAIN".equals(row.agentKey())) {
            boolean latestReady = repository.hasLatestReadyPlan(row.workId(), row.caseId());
            if (!latestReady) throw completionNotVerified();
        } else if ("ORCHESTRATOR".equals(row.agentKey())) {
            boolean responsible =
<<<<<<< HEAD
                    followupRepository.hasResponsibility(row.workId(), row.caseId())
                            || repository.hasResponsibleChild(row.caseId(), row.workId(), row.workRef());
            if (!responsible) throw completionNotVerified();
        } else if ("PROCUREMENT".equals(row.agentKey())) {
            var verifier = procurementCompletion.getIfAvailable();
            if (!repository.noPurchaseRequired(row.workId()) || verifier == null || !verifier.verified(row.caseId(), row.workId()))
=======
                    repository.hasResponsibleChild(row.caseId(), row.workId(), row.workRef());
            if (!responsible) throw completionNotVerified();
        } else if ("PROCUREMENT".equals(row.agentKey())) {
            var verifier = procurementCompletion.getIfAvailable();
            if (verifier == null || !verifier.verified(row.caseId(), row.workId()))
>>>>>>> origin/main
                throw completionNotVerified();
        } else throw completionNotVerified();
    }

    private static ResponseStatusException completionNotVerified() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "COMPLETION_NOT_VERIFIED");
    }
}
