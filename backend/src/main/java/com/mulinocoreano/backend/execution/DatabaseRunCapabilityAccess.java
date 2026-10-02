package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.security.AgentActor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DatabaseRunCapabilityAccess implements RunCapabilityAccess {
    private final RunLeaseRepository leases;

    public DatabaseRunCapabilityAccess(RunLeaseRepository leases) {
        this.leases = leases;
    }

    @Override
    public RunScope requireLocked(String token, String expectedAgentKey, String caseRef) {
        RunLeaseRepository.requireTransaction();
        var row = findLocked(token);
        if (!row.agentKey().equals(expectedAgentKey) || !row.caseRef().equals(caseRef))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Run capability does not permit this role or Case");
        return new RunScope(
                row.id(), row.caseId(), row.workId(), row.caseRef(), row.workRef(), row.agentKey());
    }

    @Transactional
    public AgentActor authenticate(String token) {
        var row = findLocked(token);
        return new AgentActor(row.ref(), row.caseRef(), row.workRef(), row.agentKey());
    }

    private RunLeaseRepository.RunRow findLocked(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid run capability");
        Long id =
                leases.findByCapabilityHash(RunTokens.hash(token))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "Invalid run capability"));
        var row = leases.lock(id);
        leases.requireLive(row);
        if (!RunTokens.matches(token, row.capabilityHash())) throw RunLeaseRepository.stale();
        return row;
    }
}
