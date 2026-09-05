package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.RunService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentWorkService {
    private final RunCapabilityAccess capabilities;
    private final RunService runs;
    private final RunExecutionService execution;
    private final RequestIdempotency idempotency;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public AgentWorkService(RunCapabilityAccess capabilities,RunService runs,RunExecutionService execution,RequestIdempotency idempotency,JdbcClient jdbc,ObjectMapper mapper) {
        this.capabilities=capabilities;this.runs=runs;this.execution=execution;this.idempotency=idempotency;this.jdbc=jdbc;this.mapper=mapper;
    }
    @Transactional public JsonNode create(String token,String key,CreateWork request) {
        RunExecutionService.required(request.caseRef(),"caseRef",20);
        var scope=capabilities.requireLocked(token,"ORCHESTRATOR",request.caseRef());
        RunExecutionService.required(request.title(),"title",200);
        if(request.agentKey()==null || !Set.of("SUPPLY_CHAIN","PROCUREMENT","QC").contains(request.agentKey())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Child agentKey must be SUPPLY_CHAIN, PROCUREMENT or QC");
        if(request.description()!=null && request.description().length()>8000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"description is too long");
        JsonNode result=idempotency.execute("work.create:"+scope.workItemRef(),key,request,()->{
            long agent=jdbc.sql("SELECT agent_id FROM agents WHERE agent_key=:key AND is_active FOR SHARE").param("key",request.agentKey()).query(Long.class).optional()
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Assigned role agent is inactive or unavailable"));
            String ref="WI-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            Map<String,Object> metadata=new LinkedHashMap<>();if(request.metadata()!=null) metadata.putAll(request.metadata());
            // Caller metadata cannot impersonate another task's ownership.
            metadata.put("parentWorkItemRef",scope.workItemRef());metadata.put("createdByRunRef",jdbc.sql("SELECT run_ref FROM runs WHERE run_id=:id").param("id",scope.runId()).query(String.class).single());
            jdbc.sql("""
                    INSERT INTO work_items(work_item_ref,case_id,title,description,assigned_agent_id,metadata)
                    VALUES(:ref,:c,:title,:description,:agent,CAST(:metadata AS jsonb))
                    """).param("ref",ref).param("c",scope.caseId()).param("title",request.title()).param("description",request.description(),java.sql.Types.VARCHAR)
                    .param("agent",agent).param("metadata",mapper.writeValueAsString(metadata)).update();
            // Participation describes durable Case responsibility; capability authorization
            // continues to come from the assigned Run role and its live lease.
            jdbc.sql("""
                    INSERT INTO case_participants(case_id,actor_type,agent_id,role)
                    VALUES(:caseId,'AGENT',:agentId,'업무 담당')
                    ON CONFLICT DO NOTHING
                    """).param("caseId",scope.caseId()).param("agentId",agent).update();
            var run=runs.createRun(new CreateRunRequest(request.agentKey(),scope.caseRef(),ref,"CODEX"),null);
            if(!"QUEUED".equals(run.status())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Child context could not be queued");
            return Map.of("workItemRef",ref,"caseRef",scope.caseRef(),"agentKey",request.agentKey(),"runRef",run.runRef(),"status","READY");
        });
        capabilities.requireLocked(token,"ORCHESTRATOR",request.caseRef());
        return result;
    }
    @Transactional public JsonNode transition(String token,String key,String agentKey,String caseRef,String workRef,Transition request) {
        var scope=capabilities.requireLocked(token,agentKey,caseRef);
        if(!scope.workItemRef().equals(workRef)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Capability is bound to another Work Item");
        return idempotency.execute("work.transition:"+scope.workItemRef(),key,request,
                ()->execution.finishAuthorized(scope.runId(),request.outcome(),request.summary(),request.waitingConditions()));
    }
    public record CreateWork(String caseRef,String agentKey,String title,String description,Map<String,Object> metadata) {}
    public record Transition(String outcome,String summary,List<RunExecutionService.Wait> waitingConditions) {}
}
