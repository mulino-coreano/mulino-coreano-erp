package com.mulinocoreano.backend.execution;

/** Server-side run authorization. Implementations must lock and revalidate the live lease
 * inside the caller's transaction; callers cannot construct their own trusted actor identity. */
public interface RunCapabilityAccess {
    RunScope requireLocked(String capabilityToken, String expectedAgentKey, String caseRef);

    record RunScope(long runId, long caseId, long workItemId,
                    String caseRef, String workItemRef, String agentKey) {}
}
