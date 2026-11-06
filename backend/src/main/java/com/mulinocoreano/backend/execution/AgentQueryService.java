package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.planning.PlanDto;
import com.mulinocoreano.backend.planning.PlanPersistenceService;
import com.mulinocoreano.backend.planning.PlanQueryRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class AgentQueryService {
    private final RunCapabilityAccess capabilities;
    private final ExecutionContextBuilder contexts;
    private final PlanPersistenceService plans;
    private final PlanQueryRepository repository;
    private final AgentPurchasingReads purchasing;

    public AgentQueryService(
            RunCapabilityAccess capabilities,
            ExecutionContextBuilder contexts,
            PlanPersistenceService plans,
            PlanQueryRepository repository,
            AgentPurchasingReads purchasing) {
        this.capabilities = capabilities;
        this.contexts = contexts;
        this.plans = plans;
        this.repository = repository;
        this.purchasing = purchasing;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> caseContext(String token, String agentKey, String caseRef) {
        var scope = capabilities.requireLocked(token, agentKey, caseRef);
        var result = contexts.build(scope.caseRef(), scope.caseId());
        capabilities.requireLocked(token, agentKey, caseRef);
        return result;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> material(String token, String agentKey, String caseRef, long id) {
        var scope = capabilities.requireLocked(token, agentKey, caseRef);
        requirePositive(id);
        var result = purchasing.material(scope.caseId(), scope.caseRef(), id);
        capabilities.requireLocked(token, agentKey, caseRef);
        return result;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public tools.jackson.databind.JsonNode purchaseOrder(
            String token, String agentKey, String caseRef, long id) {
        var scope = capabilities.requireLocked(token, agentKey, caseRef);
        requirePositive(id);
        var result = purchasing.order(scope.caseId(), id);
        capabilities.requireLocked(token, agentKey, caseRef);
        return result;
    }

    private static void requirePositive(long id) {
        if (id <= 0)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "ID must be a positive signed 64-bit integer");
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PlanDto plan(String token, String agentKey, String caseRef, String planRef) {
        var scope = capabilities.requireLocked(token, agentKey, caseRef);
        boolean sameCase = repository.belongsToCase(planRef, scope.caseId());
        if (!sameCase)
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Plan is not available in the current Case");
        PlanDto result = plans.get(planRef);
        capabilities.requireLocked(token, agentKey, caseRef);
        return result;
    }
}
