package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.planning.PlanDto;
import com.mulinocoreano.backend.planning.PlanPersistenceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient jdbc;
    public AgentQueryService(RunCapabilityAccess capabilities,ExecutionContextBuilder contexts,PlanPersistenceService plans,JdbcClient jdbc) { this.capabilities=capabilities;this.contexts=contexts;this.plans=plans;this.jdbc=jdbc; }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> caseContext(String token,String agentKey,String caseRef) {
        var scope=capabilities.requireLocked(token,agentKey,caseRef);
        var result=contexts.build(scope.caseRef(),scope.caseId());
        capabilities.requireLocked(token,agentKey,caseRef);
        return result;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public PlanDto plan(String token,String agentKey,String caseRef,String planRef) {
        var scope=capabilities.requireLocked(token,agentKey,caseRef);
        boolean sameCase=jdbc.sql("SELECT EXISTS(SELECT 1 FROM replenishment_plans WHERE plan_ref=:ref AND case_id=:c)").param("ref",planRef).param("c",scope.caseId()).query(Boolean.class).single();
        if(!sameCase) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Plan is not available in the current Case");
        return plans.get(planRef);
    }
}
