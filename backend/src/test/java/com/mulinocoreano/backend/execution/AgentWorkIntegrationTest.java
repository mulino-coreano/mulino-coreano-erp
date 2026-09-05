package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.interfacepackage.*;
import com.mulinocoreano.backend.security.WithTestActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mulino.auth.worker-client-id=test-worker", "spring.flyway.schemas=agent_work_it", "spring.datasource.hikari.schema=agent_work_it"})
@AutoConfigureMockMvc @Transactional
@WithTestActor(service=true,capabilities="worker:dispatch")
class AgentWorkIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired DispatcherService dispatcher;
    @Autowired ObjectMapper mapper;

    @Test void childCreationIsCaseBoundRoleLimitedAndIdempotent() throws Exception {
        var claim=queueAndClaim();
        Map<String,Object> payload=Map.of("caseRef",claim.caseRef(),"agentKey","SUPPLY_CHAIN","title","Calculate demand");
        String result=create(claim,payload,"child-key",200);
        assertThat(mapper.readTree(create(claim,payload,"child-key",200))).isEqualTo(mapper.readTree(result));
        create(claim,Map.of("caseRef",claim.caseRef(),"agentKey","SUPPLY_CHAIN","title","Changed"),"child-key",409);
        create(claim,Map.of("caseRef",claim.caseRef(),"agentKey","ORCHESTRATOR","title","Escalate"),"bad-role",400);
        create(claim,Map.of("caseRef","CASE-FOREIGN","agentKey","SUPPLY_CHAIN","title","Escape scope"),"other-case",403);
        assertThat(jdbc.sql("SELECT count(*) FROM work_items WHERE case_id=(SELECT case_id FROM cases WHERE case_ref=:ref)").param("ref",claim.caseRef()).query(Long.class).single()).isEqualTo(2);
    }

    @Test void assignedRolesJoinCaseOnceBeforeTheirQueuedContextsAreBuilt() throws Exception {
        var claim=queueAndClaim();
        for (String role : List.of("SUPPLY_CHAIN","PROCUREMENT","QC")) {
            Map<String,Object> request=Map.of("caseRef",claim.caseRef(),"agentKey",role,"title",role+" analysis");
            var first=mapper.readTree(create(claim,request,role+"-one",200));
            assertThat(mapper.readTree(create(claim,request,role+"-one",200))).isEqualTo(first);
            var second=mapper.readTree(create(claim,Map.of("caseRef",claim.caseRef(),"agentKey",role,"title",role+" follow-up"),role+"-two",200));
            assertThat(second.path("workItemRef").asText()).isNotEqualTo(first.path("workItemRef").asText());
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM case_participants cp JOIN cases c USING(case_id) JOIN agents a USING(agent_id)
                    WHERE c.case_ref=:caseRef AND cp.actor_type='AGENT' AND a.agent_key=:role
                    """).param("caseRef",claim.caseRef()).param("role",role).query(Long.class).single()).isEqualTo(1);
            for (var child : List.of(first,second)) {
                var context=mapper.readTree(jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_ref=:ref")
                        .param("ref",child.path("runRef").asText()).query(String.class).single());
                var participants=java.util.stream.StreamSupport.stream(context.path("organizational").spliterator(),false)
                        .filter(actor->"AGENT".equals(actor.path("actor_type").asText()) && role.equals(actor.path("actor_ref").asText())).toList();
                assertThat(participants).hasSize(1);
                assertThat(participants.getFirst().path("role").asText()).isEqualTo("업무 담당");
            }
        }
    }

    @Test void waitAndConditionsReleaseRunThenResumeOnlyAfterBothAreSatisfied() throws Exception {
        var claim=queueAndClaim();
        Map child=mapper.readValue(create(claim,Map.of("caseRef",claim.caseRef(),"agentKey","SUPPLY_CHAIN","title","Calculate"),"child",200),Map.class);
        Map<String,Object> waiting=Map.of("outcome","WAITING","summary","Await child and review date","waitingConditions",List.of(
                Map.of("type","DEPENDENCY_DONE","payload",Map.of("dependentWiRef",child.get("workItemRef")),"reason","Calculation"),
                Map.of("type","SCHEDULED_TIME","payload",Map.of("dueAt",Instant.now().plusSeconds(3600).toString()),"reason","Review date")));
        mvc.perform(post("/api/v1/agent/work-items/"+claim.workItemRef()+"/transition").header("Authorization","Bearer "+claim.capabilityToken()).header("Idempotency-Key","waiting")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(waiting))).andExpect(status().isOk());
        var receipt=execution.finish(claim.runRef(),"worker",claim.leaseToken(),"FAILED","Model crashed after CLI transition",null);
        assertThat(receipt.alreadyFinished()).isTrue();assertThat(receipt.outcome()).isEqualTo("WAITING");
        assertThat(execution.heartbeat(claim.runRef(),"worker",claim.leaseToken()).status()).isEqualTo("COMPLETED");
        jdbc.sql("UPDATE runs SET status='ABORTED',finished_at=clock_timestamp() WHERE run_ref=:ref").param("ref",child.get("runRef")).update();
        jdbc.sql("UPDATE work_items SET status='DONE',resolved_at=clock_timestamp() WHERE work_item_ref=:ref").param("ref",child.get("workItemRef")).update();
        dispatcher.ingest(new CreateEventRequest("WORK_ITEM_STATUS_CHANGED","child-done-"+UUID.randomUUID(),claim.caseRef(),child.get("workItemRef").toString(),Map.of("status","DONE")));
        assertThat(workStatus(claim.workItemRef())).isEqualTo("WAITING");
        jdbc.sql("UPDATE waiting_conditions SET condition_payload=jsonb_build_object('dueAt',:due) WHERE work_item_id=(SELECT work_item_id FROM work_items WHERE work_item_ref=:wi) AND condition_type='SCHEDULED_TIME'")
                .param("due",Instant.now().minusSeconds(1).toString()).param("wi",claim.workItemRef()).update();
        dispatcher.dispatchScheduled();
        assertThat(workStatus(claim.workItemRef())).isEqualTo("READY");
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=(SELECT work_item_id FROM work_items WHERE work_item_ref=:wi) AND status='QUEUED'").param("wi",claim.workItemRef()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void orchestratorCannotSilentlyCloseAnUnassignedGoal() throws Exception {
        var c=queueAndClaim();
        mvc.perform(post("/api/v1/agent/work-items/"+c.workItemRef()+"/transition").header("Authorization","Bearer "+c.capabilityToken()).header("Idempotency-Key","done")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"DONE\",\"summary\":\"Everything done\"}"))
                .andExpect(status().isConflict());
    }
    @Test void scopedCaseReadHasCurrentContextWithoutCredentialsAndDeniesOtherCase() throws Exception {
        var c=queueAndClaim();
        var text=mvc.perform(get("/api/v1/agent/cases/"+c.caseRef()).header("Authorization","Bearer "+c.capabilityToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(text).contains(c.caseRef()).doesNotContain(c.capabilityToken(),c.leaseToken(),"lease_token_hash");
        mvc.perform(get("/api/v1/agent/cases/CASE-OTHER").header("Authorization","Bearer "+c.capabilityToken())).andExpect(status().isForbidden());
    }
    @Test void workerAuthenticationDoesNotEnterAgentChain() throws Exception {
        mvc.perform(post("/api/v1/agent/work-items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private String create(RunExecutionService.Claim claim,Map<String,Object> payload,String key,int code) throws Exception {
        return mvc.perform(post("/api/v1/agent/work-items").header("Authorization","Bearer "+claim.capabilityToken()).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();
    }
    private String workStatus(String ref) { return jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref=:r").param("r",ref).query(String.class).single(); }
    private RunExecutionService.Claim queueAndClaim() {
        String id=UUID.randomUUID().toString().substring(0,8),c="CASE-"+id,w="WI-"+id;
        long cid=jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES(:c,'Goal','Keep supply available','ACT') RETURNING case_id").param("c",c).query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :w,:c,'Interpret goal',agent_id FROM agents WHERE agent_key='ORCHESTRATOR'").param("w",w).param("c",cid).update();
        runs.createRun(new CreateRunRequest("ORCHESTRATOR",c,w,"CODEX"),null);
        return execution.claim("worker").orElseThrow();
    }
}
