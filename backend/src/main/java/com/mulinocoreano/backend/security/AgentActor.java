package com.mulinocoreano.backend.security;

import java.util.Set;

/** Short-lived execution identity. It never carries human ERP roles or worker credentials. */
public record AgentActor(String runRef,String caseRef,String workItemRef,String agentKey) implements ErpActor {
    @Override public String issuer() { return "urn:mulino:run-capability"; }
    @Override public String subject() { return runRef; }
    @Override public Set<String> capabilities() { return Set.of("agent:"+agentKey); }
}
