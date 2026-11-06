package com.mulinocoreano.backend.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.interfacepackage.RunService;
import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 목표 4·5: 에이전트 멱등성 영수증은 한 실행(Run과 그 lease 만료 재시도)에만 속한다. 재시도는
 * 응답을 잃은 같은 시도이므로 영수증을 다시 받고, WAITING 뒤 재개된 Run은 같은 키를 보내도
 * 현재 ERP 사실로 계획을 계산하며 전이는 호출한 Run을 종료한다.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.schemas=agent_run_idempotency_it",
            "spring.flyway.clean-disabled=false",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
            "spring.datasource.hikari.schema=agent_run_idempotency_it",
            "spring.main.allow-bean-definition-overriding=true",
            "mulino.local-auth.service-secret=local-test-secret"
        })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AgentRunIdempotencyIntegrationTest {
    private static final String SECRET = "local-test-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired RunService runs;
    @Autowired ObjectMapper mapper;
    long warehouse;
    List<Long> products;
    String caseRef;
    long caseId;

    @TestConfiguration
    static class FixtureClock {
        @Bean("planningClock")
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-04T16:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas())
                .containsExactly("agent_run_idempotency_it");
        flyway.clean();
        flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);
        warehouse =
                jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'")
                        .query(Long.class)
                        .single();
        products =
                jdbc.sql(
                                "SELECT product_id FROM products WHERE sku IN"
                                        + " ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                        .query(Long.class)
                        .list();
        var created =
                mvc.perform(
                                post("/api/v1/cases")
                                        .header("X-Mulino-Local-Role", "MANAGER")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"objective\":\"replenish\"}"))
                        .andReturn()
                        .getResponse();
        assertThat(created.getStatus()).isEqualTo(200);
        caseRef = mapper.readTree(created.getContentAsString()).path("caseRef").asString();
        caseId =
                jdbc.sql("SELECT case_id FROM cases WHERE case_ref=:ref")
                        .param("ref", caseRef)
                        .query(Long.class)
                        .single();
    }

    @Test
    void resumedRunReusingPlanKeyRecalculatesCurrentFactsInsteadOfReplayingEarlierReadyPlan()
            throws Exception {
        queue("SUPPLY_CHAIN", "WI-SUPPLY");
        JsonNode first = claim("worker-1");
        JsonNode firstPlan = readJson(plan(first, "plan"));
        assertThat(firstPlan.path("result").path("status").asString()).isEqualTo("READY");
        // The same Run retrying its own request still receives its original plan.
        assertThat(readJson(plan(first, "plan"))).isEqualTo(firstPlan);
        assertThat(finish(first, "worker-1", "WAITING", List.of(scheduledWait()))).isEqualTo(200);

        makeScheduledWaitsDue();
        JsonNode second = claim("worker-2");
        assertThat(second.path("runRef").asString())
                .isNotEqualTo(first.path("runRef").asString());
        // Current stock no longer reconciles with its LOTs after the first Run planned.
        jdbc.sql("UPDATE stock SET quantity=quantity+1").update();

        assertThat(plan(second, "plan").getStatus()).isEqualTo(409);
        assertThat(openAttentions()).isEqualTo(1);
        assertThat(planningAttempts("WI-SUPPLY")).isEqualTo(2);
        assertThat(finish(second, "worker-2", "DONE", null)).isEqualTo(409);
        assertThat(workStatus("WI-SUPPLY")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void leaseExpiryRetryReusingPlanKeyReplaysTheCrashedAttemptsPlanAndCompletes()
            throws Exception {
        queue("SUPPLY_CHAIN", "WI-SUPPLY");
        JsonNode first = claim("worker-1");
        JsonNode firstPlan = readJson(plan(first, "plan"));
        assertThat(firstPlan.path("result").path("status").asString()).isEqualTo("READY");
        // The worker dies before its response is used; the lease expires and the Run is retried.
        expireLease(first.path("runRef").asString());

        JsonNode retry = claim("worker-2");
        assertThat(retryOrigin(retry.path("runRef").asString()))
                .isEqualTo(first.path("runRef").asString());
        assertThat(readJson(plan(retry, "plan"))).isEqualTo(firstPlan);
        assertThat(plans()).isEqualTo(1);
        assertThat(planningAttempts("WI-SUPPLY")).isEqualTo(1);
        assertThat(finish(retry, "worker-2", "DONE", null)).isEqualTo(200);
        assertThat(workStatus("WI-SUPPLY")).isEqualTo("DONE");
    }

    @Test
    void leaseExpiryRetryOfAResumedRunReplaysTheResumedRunsPlanNotTheOriginal()
            throws Exception {
        queue("SUPPLY_CHAIN", "WI-SUPPLY");
        JsonNode first = claim("worker-1");
        JsonNode firstPlan = readJson(plan(first, "plan"));
        assertThat(finish(first, "worker-1", "WAITING", List.of(scheduledWait()))).isEqualTo(200);

        makeScheduledWaitsDue();
        JsonNode resumed = claim("worker-2");
        assertThat(retryOrigin(resumed.path("runRef").asString())).isNull();
        JsonNode resumedPlan = readJson(plan(resumed, "plan"));
        assertThat(resumedPlan.path("ref").asString())
                .isNotEqualTo(firstPlan.path("ref").asString());
        expireLease(resumed.path("runRef").asString());

        JsonNode retry = claim("worker-3");
        assertThat(retryOrigin(retry.path("runRef").asString()))
                .isEqualTo(resumed.path("runRef").asString());
        assertThat(readJson(plan(retry, "plan"))).isEqualTo(resumedPlan);
        assertThat(plans()).isEqualTo(2);
        assertThat(planningAttempts("WI-SUPPLY")).isEqualTo(2);
    }

    @Test
    void resumedRunReusingTransitionKeyFinishesItsOwnRun() throws Exception {
        queue("QC", "WI-QC");
        JsonNode first = claim("worker-1");
        Map<String, Object> waiting =
                Map.of(
                        "outcome",
                        "WAITING",
                        "summary",
                        "inspect again later",
                        "waitingConditions",
                        List.of(scheduledWait()));
        assertThat(transition(first, "WI-QC", "finish", waiting).getStatus()).isEqualTo(200);

        makeScheduledWaitsDue();
        JsonNode second = claim("worker-1");
        String secondRun = second.path("runRef").asString();
        assertThat(secondRun).isNotEqualTo(first.path("runRef").asString());

        var resumed = transition(second, "WI-QC", "finish", waiting);
        assertThat(resumed.getStatus()).isEqualTo(200);
        assertThat(readJson(resumed).path("runRef").asString()).isEqualTo(secondRun);
        assertThat(runStatus(secondRun)).isEqualTo("COMPLETED");
        assertThat(workStatus("WI-QC")).isEqualTo("WAITING");
        assertThat(activeWaits("WI-QC")).isEqualTo(1);
    }

    private void queue(String agentKey, String workItemRef) {
        jdbc.sql(
                        "INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id)"
                                + " SELECT :wi,:case,'agent work',agent_id FROM agents"
                                + " WHERE agent_key=:agent")
                .param("wi", workItemRef)
                .param("case", caseId)
                .param("agent", agentKey)
                .update();
        runs.createRun(new CreateRunRequest(agentKey, caseRef, workItemRef, "CODEX"), null);
    }

    private JsonNode claim(String workerId) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/internal/runs/claim")
                                        .header("X-Mulino-Local-Service", SECRET)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("workerId", workerId))))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return readJson(response);
    }

    private MockHttpServletResponse plan(JsonNode claim, String key) throws Exception {
        return mvc.perform(
                        post("/api/v1/cases/" + caseRef + "/plans")
                                .header("Authorization", bearer(claim))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(planBody()))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse transition(
            JsonNode claim, String workItemRef, String key, Map<String, Object> body)
            throws Exception {
        return mvc.perform(
                        post("/api/v1/agent/work-items/" + workItemRef + "/transition")
                                .header("Authorization", bearer(claim))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private int finish(JsonNode claim, String workerId, String outcome, List<?> waits)
            throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("runRef", claim.path("runRef").asString());
        body.put("workerId", workerId);
        body.put("leaseToken", claim.path("leaseToken").asString());
        body.put("outcome", outcome);
        body.put("summary", "agent finished with " + outcome);
        if (waits != null) body.put("waitingConditions", waits);
        return mvc.perform(
                        post("/api/v1/internal/runs/finish")
                                .header("X-Mulino-Local-Service", SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static Map<String, Object> scheduledWait() {
        return Map.of(
                "type",
                "SCHEDULED_TIME",
                "payload",
                Map.of("dueAt", Instant.now().plusSeconds(3600).toString()),
                "reason",
                "check again later");
    }

    private void makeScheduledWaitsDue() {
        jdbc.sql("UPDATE waiting_conditions SET condition_payload=jsonb_build_object('dueAt',:due)")
                .param("due", Instant.now().minusSeconds(1).toString())
                .update();
    }

    private void expireLease(String runRef) {
        jdbc.sql(
                        "UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second'"
                                + " WHERE run_ref=:run")
                .param("run", runRef)
                .update();
    }

    private String planBody() {
        return mapper.writeValueAsString(Map.of("warehouseId", warehouse, "productIds", products));
    }

    private static String bearer(JsonNode claim) {
        return "Bearer " + claim.path("capabilityToken").asString();
    }

    private JsonNode readJson(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private long openAttentions() {
        return jdbc.sql("SELECT count(*) FROM attention_requests WHERE status='OPEN'")
                .query(Long.class)
                .single();
    }

    private long planningAttempts(String workItemRef) {
        return jdbc.sql(
                        "SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref=:wi")
                .param("wi", workItemRef)
                .query(Long.class)
                .single();
    }

    private String retryOrigin(String runRef) {
        return jdbc.sql(
                        "SELECT o.run_ref FROM runs r LEFT JOIN runs o ON o.run_id=r.retry_of_run_id"
                                + " WHERE r.run_ref=:run")
                .param("run", runRef)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private long plans() {
        return jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single();
    }

    private long activeWaits(String workItemRef) {
        return jdbc.sql(
                        "SELECT count(*) FROM waiting_conditions w JOIN work_items i"
                                + " ON i.work_item_id=w.work_item_id"
                                + " WHERE i.work_item_ref=:wi AND w.status='ACTIVE'")
                .param("wi", workItemRef)
                .query(Long.class)
                .single();
    }

    private String workStatus(String workItemRef) {
        return jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref=:wi")
                .param("wi", workItemRef)
                .query(String.class)
                .single();
    }

    private String runStatus(String runRef) {
        return jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:run")
                .param("run", runRef)
                .query(String.class)
                .single();
    }
}
