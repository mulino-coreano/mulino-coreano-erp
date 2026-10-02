package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.RunService;
import com.mulinocoreano.backend.security.WithTestActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.schemas=execution_result_it","spring.datasource.hikari.schema=execution_result_it"})
@AutoConfigureMockMvc @Transactional
@WithTestActor(service=true,capabilities="worker:dispatch")
class RunResultValidationIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void latestAttentionPlanPreventsHistoricalReadyPlanFromCompletingWork() throws Exception {
        var c=claim();plan(c,1,"READY");plan(c,2,"NEEDS_ATTENTION");
        finish(c,"DONE",List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("COMPLETION_NOT_VERIFIED"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
        finish(c,"FAILED",List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAILED"));
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)")
                .param("r",c.runRef()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void latestReadyPlanCanCompleteAndItsReceiptCannotBeDowngraded() throws Exception {
        var c=claim();plan(c,1,"NEEDS_ATTENTION");plan(c,2,"READY");
        finish(c,"DONE",List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DONE"));
        finish(c,"FAILED",List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DONE")).andExpect(jsonPath("$.alreadyFinished").value(true));
    }
    @Test void unsequencedLegacyPlanCannotCompleteEvenWhenMetadataClaimsReady() throws Exception {
        var c=claim();plan(c,1,"READY");
        jdbc.sql("""
                UPDATE work_items SET planning_attempt_sequence=0,latest_planning_outcome=NULL,latest_planning_plan_id=NULL,
                    metadata='{"planning_attempt_sequence":9,"latest_planning_outcome":"READY","latest_planning_plan_id":1}'::jsonb
                WHERE work_item_ref=:wi
                """).param("wi",c.workItemRef()).update();
        finish(c,"DONE",List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("COMPLETION_NOT_VERIFIED"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
    }
    @Test void dependencyAliasesCannotDisagree() throws Exception {
        var c=claim();String dependency=dependency(c);
        finish(c,"WAITING",List.of(wait("DEPENDENCY_DONE",Map.of("dependent_wi_ref",dependency,"dependentWiRef","WI-OTHER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
    }
    @Test void scheduledAliasesCannotDisagree() throws Exception {
        var c=claim();String due=Instant.now().plusSeconds(3600).toString();
        finish(c,"WAITING",List.of(wait("SCHEDULED_TIME",Map.of("due_at",due,"dueAt",Instant.now().plusSeconds(7200).toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
    }
    @Test void coherentAliasesAreAcceptedAndPersistedInOneCanonicalForm() throws Exception {
        var c=claim();String dependency=dependency(c);String due=Instant.now().plusSeconds(3600).toString();
        finish(c,"WAITING",List.of(wait("DEPENDENCY_DONE",Map.of("dependent_wi_ref",dependency,"dependentWiRef",dependency)),
                wait("SCHEDULED_TIME",Map.of("due_at",due,"dueAt",due))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("WAITING"));
        var payloads=jdbc.sql("SELECT condition_payload::text FROM waiting_conditions WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)")
                .param("r",c.runRef()).query(String.class).list();
        assertThat(payloads).hasSize(2).allSatisfy(payload->assertThat(payload).doesNotContain("dependent_wi_ref","due_at"));
    }
    @Test void lowercaseRfc3339WaitIsAcceptedAndStoredCanonically() throws Exception {
        var c=claim();
        String canonical=Instant.now().plusSeconds(3600).toString();
        String lowercase=canonical.replace('T','t').replace('Z','z');
        finish(c,"WAITING",List.of(wait("SCHEDULED_TIME",Map.of("dueAt",lowercase))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("WAITING"));
        assertThat(jdbc.sql("SELECT condition_payload->>'dueAt' FROM waiting_conditions WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)")
                .param("r",c.runRef()).query(String.class).single()).isEqualTo(canonical);
    }
    @ParameterizedTest @ValueSource(strings={"2026-02-30T10:00:00Z","2026-09-05","2026-09-05T24:00:00Z","2026-09-05T12:30:00"})
    void scheduledWaitRequiresAStrictInstant(String invalid) throws Exception {
        var c=claim();
        finish(c,"WAITING",List.of(wait("SCHEDULED_TIME",Map.of("dueAt",invalid))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
    }
    @Test void waitingListIsBoundedToSixteenAndNeverLeavesPartialRows() throws Exception {
        var c=claim();var waits=java.util.Collections.nCopies(17,wait("SCHEDULED_TIME",Map.of("dueAt",Instant.now().plusSeconds(3600).toString())));
        finish(c,"WAITING",waits).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(jdbc.sql("SELECT count(*) FROM waiting_conditions WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)")
                .param("r",c.runRef()).query(Long.class).single()).isZero();
    }
    @Test void nullWaitAndUnknownOutcomeAreInvalidResultsWithoutMutation() throws Exception {
        var c=claim();
        finish(c,"WAITING",java.util.Arrays.asList((Object)null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        finish(c,"SUCCESS",List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(runStatus(c)).isEqualTo("RUNNING");
    }
    @Test void agentTransitionAlsoPublishesSafeResultCodes() throws Exception {
        var c=claim();
        mvc.perform(post("/api/v1/agent/work-items/"+c.workItemRef()+"/transition")
                .header("Authorization","Bearer "+c.capabilityToken()).header("Idempotency-Key","invalid-agent-result")
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"WAITING\",\"summary\":\"Wait without a condition\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_RESULT"));
    }
    @Test void staleLeaseHasADistinctSafeCode() throws Exception {
        var c=claim();
        mvc.perform(post("/api/v1/internal/runs/heartbeat").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "runRef",c.runRef(),"workerId","result-worker","leaseToken","old-token"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("STALE_LEASE"));
    }
    private org.springframework.test.web.servlet.ResultActions finish(RunExecutionService.Claim c,String outcome,List<?> waits) throws Exception {
        return mvc.perform(post("/api/v1/internal/runs/finish").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "runRef",c.runRef(),"workerId","result-worker","leaseToken",c.leaseToken(),"outcome",outcome,"summary","Model result","waitingConditions",waits))));
    }
    private Map<String,Object> wait(String type,Map<String,Object> payload) {return Map.of("type",type,"payload",payload,"reason","Waiting reason");}
    private String runStatus(RunExecutionService.Claim c) {return jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:r").param("r",c.runRef()).query(String.class).single();}
    private RunExecutionService.Claim claim() {
        String id=UUID.randomUUID().toString().substring(0,8),cr="CASE-"+id,wi="WI-"+id;
        long cid=jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES(:r,'Results','Validate latest plan','ACT') RETURNING case_id").param("r",cr).query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :w,:c,'Calculate',agent_id FROM agents WHERE agent_key='SUPPLY_CHAIN'").param("w",wi).param("c",cid).update();
        runs.createRun(new CreateRunRequest("SUPPLY_CHAIN",cr,wi,"CODEX"),null);
        return execution.claim("result-worker").orElseThrow();
    }
    private String dependency(RunExecutionService.Claim c) {
        String ref="WI-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :w,case_id,'Dependent work',agent_id FROM runs WHERE run_ref=:r").param("w",ref).param("r",c.runRef()).update();return ref;
    }
    private void plan(RunExecutionService.Claim c,int version,String state) {
        long warehouse=jdbc.sql("SELECT warehouse_id FROM planning_cases p JOIN cases c USING(case_id) WHERE c.case_ref=:r").param("r",c.caseRef()).query(Long.class).optional().orElseGet(()->{
            long id=jdbc.sql("INSERT INTO warehouses(name,type) VALUES('Result plan warehouse','AMBIENT') RETURNING warehouse_id").query(Long.class).single();
            jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) SELECT case_id,:w FROM cases WHERE case_ref=:r").param("w",id).param("r",c.caseRef()).update();return id;
        });
        long planId = jdbc.sql("""
                INSERT INTO replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash,created_by_work_item_id)
                SELECT :p,case_id,:warehouse,:version,clock_timestamp(),30,CURRENT_DATE+29,'{}'::jsonb,jsonb_build_object('status',:status),:hash,:hash,work_item_id
                FROM runs WHERE run_ref=:r
                RETURNING replenishment_plan_id
                """).param("p","PLAN-"+UUID.randomUUID()).param("warehouse",warehouse).param("version",version).param("status",state).param("hash","0".repeat(64)).param("r",c.runRef()).query(Long.class).single();
        // Reproduce the trusted calculation service's marker effect, not caller metadata.
        jdbc.sql("""
                UPDATE work_items SET planning_attempt_sequence=planning_attempt_sequence+1,
                    latest_planning_outcome=:state,latest_planning_plan_id=:plan
                WHERE work_item_ref=:wi
                """).param("state",state).param("plan",planId).param("wi",c.workItemRef()).update();
    }
}
