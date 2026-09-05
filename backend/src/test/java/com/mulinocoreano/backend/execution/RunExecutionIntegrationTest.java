package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.RunService;
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
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mulino.auth.worker-client-id=test-worker", "spring.flyway.schemas=execution_it", "spring.datasource.hikari.schema=execution_it"})
@AutoConfigureMockMvc
@Transactional
@WithTestActor(service=true, capabilities="worker:dispatch")
class RunExecutionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired ObjectMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.mulinocoreano.backend.interfacepackage.ContextSnapshotService contexts;

    @Test void claimReturnsDistinctTokensAndPreservesQueuedSnapshot() throws Exception {
        String ref = queue("SUPPLY_CHAIN");
        Map<String,Object> claim = claim();
        assertThat(claim.get("runRef")).isEqualTo(ref);
        assertThat(claim.get("runtime")).isEqualTo("CODEX");
        assertThat(claim.get("timeoutSeconds")).isEqualTo(600);
        assertThat(claim.get("leaseToken").toString()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(claim.get("capabilityToken")).isNotEqualTo(claim.get("leaseToken"));
        assertThat(jdbc.sql("SELECT row_to_json(r)::text FROM runs r WHERE run_ref=:r").param("r",ref).query(String.class).single())
                .doesNotContain(claim.get("leaseToken").toString(),claim.get("capabilityToken").toString());
        assertThat(jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:r").param("r",ref).query(String.class).single()).isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT context_snapshot IS NOT NULL AND execution_context IS NOT NULL FROM runs WHERE run_ref=:r").param("r",ref).query(Boolean.class).single()).isTrue();
        mvc.perform(post("/api/v1/internal/runs/claim").contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-b\"}"))
                .andExpect(status().isNoContent());
    }

    @Test void failedClaimContextLeavesQueuedAuditUntouchedAndBlocksWithAttention() {
        String run=queue("SUPPLY_CHAIN");
        String before=jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_ref=:r").param("r",run).query(String.class).single();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("MISSING_CURRENT_FACTS")).when(contexts).build(org.mockito.ArgumentMatchers.anyString());
        assertThat(execution.claim("worker-failure")).isEmpty();
        assertThat(jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:r").param("r",run).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_ref=:r").param("r",run).query(String.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)").param("r",run).query(Long.class).single()).isEqualTo(1);
    }

    @Test void alreadyLeasedWorkerReceivesSafeConflictCode() throws Exception {
        queue("SUPPLY_CHAIN");claim();
        mvc.perform(post("/api/v1/internal/runs/claim").contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-a\"}"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("WORKER_ALREADY_LEASED"));
    }

    @Test void retryContextFailureBlocksInsteadOfLeavingUnscheduledReadyWork() throws Exception {
        String original=queue("SUPPLY_CHAIN");claim();expire(original);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("MISSING_CURRENT_FACTS")).when(contexts).build(org.mockito.ArgumentMatchers.anyString());
        assertThat(execution.claim("worker-recovery")).isEmpty();
        assertThat(jdbc.sql("SELECT w.status::text FROM work_items w JOIN runs r ON r.work_item_id=w.work_item_id WHERE r.run_ref=:r").param("r",original).query(String.class).single()).isEqualTo("BLOCKED");
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)").param("r",original).query(Long.class).single()).isEqualTo(1);
    }

    @Test void staleLeaseCannotHeartbeatOrFinish() throws Exception {
        queue("SUPPLY_CHAIN"); Map<String,Object> claim=claim();
        Map<String,Object> stale=Map.of("runRef",claim.get("runRef"),"workerId","worker-a","leaseToken","stale");
        mvc.perform(post("/api/v1/internal/runs/heartbeat").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(stale))).andExpect(status().isConflict());
        var finish=new java.util.LinkedHashMap<>(stale); finish.put("outcome","DONE"); finish.put("summary","claimed complete");
        mvc.perform(post("/api/v1/internal/runs/finish").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(finish))).andExpect(status().isConflict());
    }

    @Test void supplyChainCannotDeclareDoneWithoutPersistedPlan() throws Exception {
        queue("SUPPLY_CHAIN"); Map<String,Object> c=claim();
        mvc.perform(post("/api/v1/internal/runs/finish").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "runRef",c.get("runRef"),"workerId","worker-a","leaseToken",c.get("leaseToken"),"outcome","DONE","summary","done"))))
                .andExpect(status().isConflict());
    }

    @Test @WithTestActor(capabilities="worker:dispatch")
    void humanCannotDispatchEvenWithForgedAuthority() throws Exception {
        mvc.perform(post("/api/v1/internal/runs/claim").contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"human\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void expiryRetriesOnceThenBlocksAndRaisesOneAttention() throws Exception {
        String original=queue("SUPPLY_CHAIN"); Map<String,Object> first=claim();
        expire(original); Map<String,Object> second=claim();
        assertThat(second.get("runRef")).isNotEqualTo(original);
        assertThat(jdbc.sql("SELECT attempt FROM runs WHERE run_ref=:r").param("r",second.get("runRef")).query(Integer.class).single()).isEqualTo(2);
        expire(second.get("runRef").toString());
        for (int i=0;i<2;i++) mvc.perform(post("/api/v1/internal/runs/claim").contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-a\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)").param("r",original).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT w.status::text FROM work_items w JOIN runs r ON w.work_item_id=r.work_item_id WHERE r.run_ref=:r").param("r",original).query(String.class).single()).isEqualTo("BLOCKED");
    }

    @Test void inactiveAgentCannotUseLease() throws Exception {
        String run=queue("SUPPLY_CHAIN"); Map<String,Object> c=claim();
        jdbc.sql("UPDATE agents SET is_active=false WHERE agent_key='SUPPLY_CHAIN'").update();
        mvc.perform(post("/api/v1/internal/runs/heartbeat").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "runRef",run,"workerId","worker-a","leaseToken",c.get("leaseToken"))))).andExpect(status().isConflict());
    }

    @Test void reassignedWorkItemRevokesTheOldCapabilityAndLease() throws Exception {
        String run=queue("SUPPLY_CHAIN"); Map<String,Object> c=claim();
        jdbc.sql("UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE agent_key='QC') WHERE work_item_id=(SELECT work_item_id FROM runs WHERE run_ref=:r)").param("r",run).update();
        mvc.perform(post("/api/v1/internal/runs/heartbeat").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                "runRef",run,"workerId","worker-a","leaseToken",c.get("leaseToken"))))).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/agent/cases/"+c.get("caseRef"))
                .header("Authorization","Bearer "+c.get("capabilityToken"))).andExpect(status().isConflict());
    }

    @Test void capabilityIsRejectedByInternalWorkerRoute() throws Exception {
        queue("SUPPLY_CHAIN"); Map<String,Object> c=claim();
        mvc.perform(post("/api/v1/internal/runs/claim").header("Authorization","Bearer "+c.get("capabilityToken"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-a\"}"))
                .andExpect(status().isUnauthorized());
    }

    private void expire(String ref) {
        jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_ref=:r").param("r",ref).update();
    }

    private Map<String,Object> claim() throws Exception {
        String json=mvc.perform(post("/api/v1/internal/runs/claim").contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"worker-a\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(json,Map.class);
    }
    private String queue(String role) {
        String suffix=UUID.randomUUID().toString().substring(0,8), cr="CASE-"+suffix, wi="WI-"+suffix;
        jdbc.sql("INSERT INTO agents(agent_key,display_name) VALUES (:a,:a) ON CONFLICT(agent_key) DO NOTHING").param("a",role).update();
        long cid=jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES (:r,'Execution','Execute','ACT') RETURNING case_id").param("r",cr).query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :w,:c,'Execute',agent_id FROM agents WHERE agent_key=:a")
                .param("w",wi).param("c",cid).param("a",role).update();
        return runs.createRun(new CreateRunRequest(role,cr,wi,"CODEX"),null).runRef();
    }
}
