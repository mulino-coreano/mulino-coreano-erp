package com.mulinocoreano.backend.planning;

import com.mulinocoreano.backend.interfacepackage.CreateRunRequest;
import com.mulinocoreano.backend.execution.RunCapabilityAccess;
import com.mulinocoreano.backend.interfacepackage.RunService;
import com.mulinocoreano.backend.security.ActorAuthenticationToken;
import com.mulinocoreano.backend.security.HumanActor;
import com.mulinocoreano.backend.security.ServiceActor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL, signed run capabilities and HTTP filters; all destructive cleanup is isolated to this test schema. */
@SpringBootTest(properties = {
        "spring.flyway.schemas=plan_persistence_it", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=plan_persistence_it",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class PlanPersistenceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RunService runs;
    @Autowired Flyway flyway;
    @MockitoSpyBean ReplenishmentCalculator calculator;
    @MockitoSpyBean RunCapabilityAccess capabilities;
    private long warehouse;
    private List<Long> products;
    private String token;
    private JsonNode currentClaim;
    private String currentWorker;

    @TestConfiguration
    static class FixedPlanningTime {
        @Bean("planningClock") @Primary
        Clock planningClock() { return Clock.fixed(Instant.parse("2026-09-04T16:23:45Z"), ZoneId.of("Asia/Seoul")); }
    }

    @BeforeEach
    void setup() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("plan_persistence_it");
        flyway.clean();
        flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);
        warehouse = id("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'");
        products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                .query(Long.class).list();
        token = claim("CASE-PLAN-1", "WI-PLAN-1", "SUPPLY_CHAIN");
    }

    @Test
    void storesExactCalculationActualSourcesAndHumanReadableVersion() throws Exception {
        var before = erpState();
        var result = calculate("CASE-PLAN-1", token, "first", request(), 200);
        assertThat(result.path("version").asInt()).isEqualTo(1);
        assertThat(result.path("result").path("totalAmount").decimalValue()).isEqualByComparingTo("16500");
        assertThat(result.path("result").path("status").asText()).isEqualTo("READY");
        assertThat(result.path("asOf").asText()).isEqualTo("2026-09-04T16:23:45Z");
        assertThat(result.path("sourceSnapshot").path("asOf").asText()).isEqualTo("2026-09-05");
        assertThat(result.path("sourceSnapshot").path("products").get(0).path("item").path("id").asLong()).isIn(products);
        assertThat(result.path("sourceSnapshot").path("sourceFacts").size()).isGreaterThan(50);
        assertThat(result.path("hash").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("sourceHash").asText()).matches("[0-9a-f]{64}");
        var read = mvc.perform(get("/api/v1/plans/" + result.path("ref").asText()).with(authentication(human())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(read)).isEqualTo(result);
        assertThat(read).doesNotContain(token, "leaseToken", "capabilityToken");
        assertThat(erpState()).isEqualTo(before);
        assertThat(id("SELECT created_by_work_item_id FROM replenishment_plans"))
                .isEqualTo(id("SELECT work_item_id FROM work_items WHERE work_item_ref='WI-PLAN-1'"));
        assertThat(id("SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref='WI-PLAN-1'")).isEqualTo(1);
        assertThat(id("SELECT latest_planning_plan_id FROM work_items WHERE work_item_ref='WI-PLAN-1'"))
                .isEqualTo(id("SELECT replenishment_plan_id FROM replenishment_plans"));
    }

    @Test
    void replayReturnsOriginalAndNewKeyAppendsFreshSourceVersion() throws Exception {
        var first = calculate("CASE-PLAN-1", token, "one", request(), 200);
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=unit_price+17").update();
        jdbc.sql("UPDATE planning_policies SET horizon_days=29 WHERE warehouse_id=:id").param("id", warehouse).update();
        assertThat(calculate("CASE-PLAN-1", token, "one", Map.of("warehouseId", warehouse,
                "productIds", List.of(products.getLast(), products.getFirst(), products.getFirst())), 200)).isEqualTo(first);
        var next = calculate("CASE-PLAN-1", token, "two", request(), 200);
        assertThat(next.path("version").asInt()).isEqualTo(2);
        assertThat(next.path("horizonDays").asInt()).isEqualTo(29);
        assertThat(next.path("sourceHash").asText()).isNotEqualTo(first.path("sourceHash").asText());
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("UPDATE replenishment_plans SET version=99 WHERE version=1").update())
                .hasMessageContaining("append-only");
        var stored = jdbc.sql("SELECT source_snapshot::text FROM replenishment_plans WHERE version=1").query(String.class).single();
        assertThat(mapper.readTree(stored)).isEqualTo(first.path("sourceSnapshot"));
    }

    @Test
    void rejectsChangedInputForSameKeyAndInvalidOrCallerSuppliedDate() throws Exception {
        calculate("CASE-PLAN-1", token, "same", request(), 200);
        calculate("CASE-PLAN-1", token, "same", Map.of("warehouseId", warehouse,"productIds",products,"horizonDays",20), 409);
        calculate("CASE-PLAN-1", token, "bad", Map.of("warehouseId", warehouse,"productIds",products,"horizonDays",0), 400);
        calculate("CASE-PLAN-1", token, "dated", Map.of("warehouseId", warehouse,"productIds",products,"asOf","1990-01-01"), 400);
        calculate("CASE-PLAN-1", token, " ", request(), 400);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(1);
    }

    @Test
    void humanTargetDateDeterminesOmittedHorizonInsteadOfWarehouseDefault() throws Exception {
        structuredScope("2026-09-10");
        var result = calculate("CASE-PLAN-1", token, "scoped", request(), 200);
        assertThat(result.path("horizonDays").asInt()).isEqualTo(6);
        assertThat(result.path("targetDate").asText()).isEqualTo("2026-09-10");
        assertThat(result.path("sourceSnapshot").path("horizonDays").asInt()).isEqualTo(6);
        jdbc.sql("UPDATE planning_policies SET horizon_days=20").update();
        structuredScope("2026-09-04");
        assertThat(calculate("CASE-PLAN-1", token, "scoped", request(), 200)).isEqualTo(result);
    }

    @Test
    void scopedCaseRejectsAgentChangesToProductsWarehouseOrDeadline() throws Exception {
        structuredScope("2026-09-10");
        calculate("CASE-PLAN-1", token, "wrong-product", Map.of("warehouseId", warehouse,"productIds",List.of(products.getFirst())), 403);
        calculate("CASE-PLAN-1", token, "wrong-warehouse", Map.of("warehouseId", warehouse+1000,"productIds",products), 403);
        calculate("CASE-PLAN-1", token, "wrong-deadline", Map.of("warehouseId", warehouse,"productIds",products,"horizonDays",30), 403);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isZero();
        assertThat(id("SELECT count(*) FROM request_idempotency WHERE scope LIKE 'plans:%'")).isZero();
        assertThat(id("SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref='WI-PLAN-1'")).isZero();
        assertThat(jdbc.sql("SELECT latest_planning_outcome IS NULL AND latest_planning_plan_id IS NULL FROM work_items WHERE work_item_ref='WI-PLAN-1'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void elapsedHumanDeadlineStoresAnAttentionWithoutInventingANewDeadline() throws Exception {
        structuredScope("2026-09-04");
        var error = calculate("CASE-PLAN-1", token, "elapsed", request(), 409);
        assertThat(error.path("error").asText()).isEqualTo("CASE_TARGET_DATE_PASSED");
        assertThat(error.path("attention").path("question").asText()).isNotBlank();
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isZero();
        assertThat(id("SELECT count(*) FROM attention_requests")).isEqualTo(1);
        assertThat(calculate("CASE-PLAN-1", token, "elapsed", request(), 409)).isEqualTo(error);
    }

    @Test
    void warehouseCoordinationIsHeldBeforeLockingTheRunAndCase() throws Exception {
        doAnswer(invocation -> {
            boolean coordinated = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'
                      AND classid::bigint=(hashtext('planning.warehouse')::bigint & 4294967295)
                      AND objid::bigint=(hashtext(:warehouse)::bigint & 4294967295) AND objsubid=2 AND granted)
                    """).param("warehouse",String.valueOf(warehouse)).query(Boolean.class).single();
            assertThat(coordinated).as("Warehouse coordination must precede capability row locks").isTrue();
            return invocation.callRealMethod();
        }).when(capabilities).requireLocked(anyString(),eq("SUPPLY_CHAIN"),eq("CASE-PLAN-1"));
        calculate("CASE-PLAN-1", token, "ordered-locks", request(), 200);
    }

    private void structuredScope(String targetDate) {
        jdbc.sql("UPDATE cases SET metadata=CAST(:metadata AS jsonb) WHERE case_ref='CASE-PLAN-1'")
                .param("metadata",mapper.writeValueAsString(Map.of("replenishment", Map.of("warehouseId",warehouse,
                        "productIds",products,"targetDate",targetDate,"horizonDays",6)))).update();
    }

    @Test
    void sameWarehouseInAnotherCaseReturnsExistingCaseAndDifferentWarehouseIsIndependent() throws Exception {
        calculate("CASE-PLAN-1", token, "first", request(), 200);
        String otherToken = claim("CASE-PLAN-2", "WI-PLAN-2", "SUPPLY_CHAIN");
        var conflict = calculate("CASE-PLAN-2", otherToken, "first", request(), 409);
        assertThat(conflict.path("existingCaseRef").asText()).isEqualTo("CASE-PLAN-1");
        long otherWarehouse = jdbc.sql("INSERT INTO warehouses(name,plant_id,type) SELECT '다른 창고','DEMO-KR-02',type FROM warehouses WHERE plant_id='DEMO-KR-01' RETURNING warehouse_id")
                .query(Long.class).single();
        jdbc.sql("INSERT INTO planning_policies(warehouse_id,history_start_date,horizon_days,safety_stock_days) SELECT :other,history_start_date,horizon_days,safety_stock_days FROM planning_policies WHERE warehouse_id=:id")
                .param("other", otherWarehouse).param("id", warehouse).update();
        jdbc.sql("INSERT INTO stock(product_id,warehouse_id,quantity) SELECT product_id,:other,0 FROM products")
                .param("other", otherWarehouse).update();
        var next = calculate("CASE-PLAN-2", otherToken, "second", Map.of("warehouseId",otherWarehouse,"productIds",products), 200);
        assertThat(next.path("version").asInt()).isEqualTo(1);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(2);
    }

    @Test
    void insufficientHistoryPersistsExplicitAttentionAndNoPurchasePermission() throws Exception {
        jdbc.sql("UPDATE planning_policies SET history_start_date='2026-08-25'").update();
        var result = calculate("CASE-PLAN-1", token, "missing", request(), 200);
        assertThat(result.path("result").path("status").asText()).isEqualTo("NEEDS_ATTENTION");
        assertThat(result.path("attention").path("question").asText()).isNotBlank();
        assertThat(result.path("attention").path("consequence").asText()).contains("발주");
        assertThat(result.path("result").path("issues").get(0).path("code").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(id("SELECT count(*) FROM attention_requests WHERE status='OPEN'")).isEqualTo(1);
        assertThat(calculate("CASE-PLAN-1", token, "missing", request(), 200)).isEqualTo(result);
        assertThat(id("SELECT count(*) FROM attention_requests")).isEqualTo(1);
    }

    @Test
    void inconsistentStockStoresOneAttentionFailureWithoutFabricatingSnapshot() throws Exception {
        jdbc.sql("UPDATE stock SET quantity=quantity+1 WHERE warehouse_id=:id").param("id",warehouse).update();
        var result = calculate("CASE-PLAN-1", token, "broken", request(), 409);
        assertThat(result.path("error").asText()).isEqualTo("STOCK_LOT_MISMATCH");
        assertThat(result.path("attention").path("attentionRequestId").asLong()).isPositive();
        assertThat(result.toString()).doesNotContain("SELECT", "password");
        assertThat(calculate("CASE-PLAN-1", token, "broken", request(), 409)).isEqualTo(result);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isZero();
        assertThat(id("SELECT count(*) FROM attention_requests")).isEqualTo(1);
    }

    @Test
    void replayStillRequiresLiveScopedLeaseAndHumanOrServiceCannotCalculate() throws Exception {
        calculate("CASE-PLAN-1", token, "one", request(), 200);
        mvc.perform(post("/api/v1/cases/CASE-PLAN-1/plans").with(authentication(human()))
                .header("Idempotency-Key","human").contentType("application/json").content(mapper.writeValueAsString(request())))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/cases/CASE-PLAN-1/plans").with(authentication(worker()))
                .header("Idempotency-Key","worker").contentType("application/json").content(mapper.writeValueAsString(request())))
                .andExpect(status().is4xxClientError());
        calculate("CASE-PLAN-2", token, "other", request(), 403);
        jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE work_item_id=(SELECT work_item_id FROM work_items WHERE work_item_ref='WI-PLAN-1')").update();
        mvc.perform(post("/api/v1/cases/CASE-PLAN-1/plans").header("Authorization","Bearer "+token)
                .header("Idempotency-Key","one").contentType("application/json").content(mapper.writeValueAsString(request())))
                .andExpect(status().is4xxClientError());
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreatesOneVersion() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> calculate("CASE-PLAN-1", token, "race", request(), 200));
            var b = pool.submit(() -> calculate("CASE-PLAN-1", token, "race", request(), 200));
            assertThat(a.get()).isEqualTo(b.get());
        }
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(1);
    }

    @Test
    void concurrentNewKeysSerializeDistinctVersionsOfTheSameCase() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> calculate("CASE-PLAN-1", token, "version-a", request(), 200));
            var b = pool.submit(() -> calculate("CASE-PLAN-1", token, "version-b", request(), 200));
            assertThat(List.of(a.get().path("version").asInt(), b.get().path("version").asInt()))
                    .containsExactlyInAnyOrder(1, 2);
        }
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(2);
    }

    @Test
    void humanIntakeAndGenericCaseCalculationCompeteWithoutDeadlockOrDuplicateWarehouseBinding() throws Exception {
        long humanId = jdbc.sql("INSERT INTO users(name,email,password,role,is_active) VALUES('Race test manager','plan-race@example.invalid',NULL,'MANAGER',true) RETURNING user_id")
                .query(Long.class).single();
        var start = new CountDownLatch(1);
        var intake = Map.of("objective","목표일까지 완제품 보충","replenishment",Map.of("warehouseId",warehouse,
                "productSkus",List.of("DEMO-AMR","DEMO-BSC"),"targetDate","2026-09-10"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var planning = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/cases/CASE-PLAN-1/plans").header("Authorization","Bearer "+token)
                        .header("Idempotency-Key","mixed-plan").contentType("application/json").content(mapper.writeValueAsString(request())))
                        .andReturn().getResponse().getStatus();
            });
            var human = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/cases").with(authentication(human(humanId)))
                        .header("Idempotency-Key","mixed-intake").contentType("application/json").content(mapper.writeValueAsString(intake)))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(planning.get(15,TimeUnit.SECONDS),human.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200,409);
        }
        assertThat(id("SELECT count(*) FROM planning_cases WHERE status='ACTIVE'")).isEqualTo(1);
    }

    @Test
    void leaseExpiryDuringCalculationRollsBackPlanBindingAttentionAndReplayRecord() throws Exception {
        jdbc.sql("UPDATE suppliers SET is_active=false").update();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE work_item_id=(SELECT work_item_id FROM work_items WHERE work_item_ref='WI-PLAN-1')").update();
            return result;
        }).when(calculator).calculate(any());
        mvc.perform(post("/api/v1/cases/CASE-PLAN-1/plans").header("Authorization","Bearer "+token)
                .header("Idempotency-Key","expires").contentType("application/json").content(mapper.writeValueAsString(request())))
                .andExpect(status().is4xxClientError());
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isZero();
        assertThat(id("SELECT count(*) FROM planning_cases")).isZero();
        assertThat(id("SELECT count(*) FROM attention_requests")).isZero();
        assertThat(id("SELECT count(*) FROM request_idempotency WHERE scope LIKE 'plans:%'")).isZero();
        assertThat(id("SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref='WI-PLAN-1'")).isZero();
        assertThat(jdbc.sql("SELECT latest_planning_outcome IS NULL AND latest_planning_plan_id IS NULL FROM work_items WHERE work_item_ref='WI-PLAN-1'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void procurementCapabilityCannotCalculate() throws Exception {
        String procurement = claim("CASE-PLAN-2", "WI-PLAN-2", "PROCUREMENT");
        calculate("CASE-PLAN-2", procurement, "wrong-role", request(), 403);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isZero();
    }

    @Test
    void readyStoredPlanAllowsTheOriginSupplyChainWorkToFinish() throws Exception {
        calculate("CASE-PLAN-1", token, "ready", request(), 200);
        var receipt = finish(200);
        assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref='WI-PLAN-1'").query(String.class).single()).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT status::text FROM cases WHERE case_ref='CASE-PLAN-1'").query(String.class).single())
                .isNotIn("RESOLVED", "CLOSED");
    }

    @Test
    void attentionPlanCannotBeUsedAsSuccessfulSupplyChainCompletion() throws Exception {
        jdbc.sql("UPDATE planning_policies SET history_start_date='2026-08-25'").update();
        calculate("CASE-PLAN-1", token, "attention", request(), 200);
        finish(409);
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref='WI-PLAN-1'").query(String.class).single()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void newerAttentionOnlyFailurePreventsCompletionUsingAnOlderReadyPlan() throws Exception {
        var ready = calculate("CASE-PLAN-1", token, "ready-before-error", request(), 200);
        jdbc.sql("UPDATE stock SET quantity=quantity+1 WHERE warehouse_id=:id").param("id",warehouse).update();
        calculate("CASE-PLAN-1", token, "latest-error", request(), 409);
        assertThat(id("SELECT count(*) FROM replenishment_plans")).isEqualTo(1);
        assertThat(calculate("CASE-PLAN-1", token, "ready-before-error", request(), 200)).isEqualTo(ready);
        assertThat(id("SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref='WI-PLAN-1'")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT latest_planning_outcome='DATA_ERROR' AND latest_planning_plan_id IS NULL FROM work_items WHERE work_item_ref='WI-PLAN-1'")
                .query(Boolean.class).single()).isTrue();
        finish(409);
    }

    @Test
    void aFreshSuccessfulAttemptCanReplaceFailureButFailureReplayCannotRewindIt() throws Exception {
        jdbc.sql("UPDATE stock SET quantity=quantity+1 WHERE warehouse_id=:id").param("id",warehouse).update();
        var failure = calculate("CASE-PLAN-1", token, "data-error", request(), 409);
        jdbc.sql("UPDATE stock SET quantity=quantity-1 WHERE warehouse_id=:id").param("id",warehouse).update();
        calculate("CASE-PLAN-1", token, "recovered", request(), 200);
        assertThat(calculate("CASE-PLAN-1", token, "data-error", request(), 409)).isEqualTo(failure);
        assertThat(id("SELECT planning_attempt_sequence FROM work_items WHERE work_item_ref='WI-PLAN-1'")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT latest_planning_outcome='READY' AND latest_planning_plan_id IS NOT NULL FROM work_items WHERE work_item_ref='WI-PLAN-1'")
                .query(Boolean.class).single()).isTrue();
        finish(200);
    }

    @Test
    void markerCannotPointToAPlanFromAnotherCaseOrWorkItem() throws Exception {
        calculate("CASE-PLAN-1", token, "owned", request(), 200);
        claim("CASE-PLAN-2", "WI-PLAN-2", "SUPPLY_CHAIN");
        assertThatThrownBy(() -> jdbc.sql("UPDATE work_items SET planning_attempt_sequence=1,latest_planning_outcome='READY',latest_planning_plan_id=(SELECT replenishment_plan_id FROM replenishment_plans LIMIT 1) WHERE work_item_ref='WI-PLAN-2'").update())
                .hasMessageContaining("fk_work_item_latest_planning_plan");
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title) SELECT 'WI-PLAN-OTHER',case_id,'별도 업무' FROM cases WHERE case_ref='CASE-PLAN-1'").update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE work_items SET planning_attempt_sequence=1,latest_planning_outcome='READY',latest_planning_plan_id=(SELECT replenishment_plan_id FROM replenishment_plans LIMIT 1) WHERE work_item_ref='WI-PLAN-OTHER'").update())
                .hasMessageContaining("fk_work_item_latest_planning_plan");
    }

    @Test
    void nextClaimRefreshesBusinessFactsAndKeepsOriginalPlanAndQueuedContext() throws Exception {
        var plan = calculate("CASE-PLAN-1", token, "original", request(), 200);
        finish(200);
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT 'WI-PLAN-NEXT',c.case_id,'후속 검토',a.agent_id FROM cases c,agents a WHERE c.case_ref='CASE-PLAN-1' AND a.agent_key='SUPPLY_CHAIN'").update();
        var queued = runs.createRun(new CreateRunRequest("SUPPLY_CHAIN","CASE-PLAN-1","WI-PLAN-NEXT","CODEX"),null);
        String before = jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_id=:id").param("id",queued.runId()).query(String.class).single();
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=unit_price+17").update();
        String response = mvc.perform(post("/api/v1/internal/runs/claim").with(authentication(worker()))
                .contentType("application/json").content("{\"workerId\":\"plan-next-worker\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode claim = mapper.readTree(response);
        assertThat(claim.path("context").path("latestPlan").path("sourceSnapshot")).isEqualTo(plan.path("sourceSnapshot"));
        assertThat(claim.path("context").path("latestPlan").path("result")).isEqualTo(plan.path("result"));
        var oldTerms = java.util.stream.StreamSupport.stream(plan.path("sourceSnapshot").path("sourceFacts").spliterator(),false)
                .filter(fact -> fact.path("sourceRef").asText().startsWith("supplier_material_terms:")).toList();
        var newTerms = java.util.stream.StreamSupport.stream(claim.path("context").path("currentBusinessFacts").path("sourceFacts").spliterator(),false)
                .filter(fact -> fact.path("sourceRef").asText().startsWith("supplier_material_terms:")).toList();
        assertThat(newTerms).hasSameSizeAs(oldTerms).isNotEmpty();
        for (int i=0;i<oldTerms.size();i++) {
            assertThat(newTerms.get(i).path("values").path("unit_price").decimalValue())
                    .isEqualByComparingTo(oldTerms.get(i).path("values").path("unit_price").decimalValue().add(new java.math.BigDecimal("17")));
        }
        assertThat(jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_id=:id").param("id",queued.runId()).query(String.class).single()).isEqualTo(before);
        String scoped = mvc.perform(get("/api/v1/agent/plans/"+plan.path("ref").asText())
                .header("Authorization","Bearer "+claim.path("capabilityToken").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(scoped)).isEqualTo(plan);
        String other = claim("CASE-PLAN-2", "WI-PLAN-2", "SUPPLY_CHAIN");
        mvc.perform(get("/api/v1/agent/plans/"+plan.path("ref").asText()).header("Authorization","Bearer "+other))
                .andExpect(status().isNotFound());
    }

    private JsonNode finish(int expectedStatus) throws Exception {
        var body = Map.of("runRef",currentClaim.path("runRef").asText(),"workerId",currentWorker,
                "leaseToken",currentClaim.path("leaseToken").asText(),"outcome","DONE","summary","계획 검증 완료");
        String response = mvc.perform(post("/api/v1/internal/runs/finish").with(authentication(worker()))
                .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
    }

    private String claim(String caseRef,String workRef,String agentKey) throws Exception {
        jdbc.sql("INSERT INTO agents(agent_key,display_name,role_scope) VALUES(:key,:key,'planning') ON CONFLICT(agent_key) DO NOTHING")
                .param("key",agentKey).update();
        jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES(:ref,'재보충 계획','30일 계획','ACT')")
                .param("ref",caseRef).update();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :ref,c.case_id,'계산',a.agent_id FROM cases c,agents a WHERE c.case_ref=:caseRef AND a.agent_key=:agent")
                .param("ref",workRef).param("caseRef",caseRef).param("agent",agentKey).update();
        runs.createRun(new CreateRunRequest(agentKey,caseRef,workRef,"CODEX"),null);
        currentWorker = "plan-store-" + workRef;
        var json = mvc.perform(post("/api/v1/internal/runs/claim").with(authentication(worker()))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("workerId",currentWorker))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        currentClaim = mapper.readTree(json);
        return currentClaim.path("capabilityToken").asText();
    }
    private JsonNode calculate(String caseRef,String capability,String key,Map<String,?> request,int status) throws Exception {
        String response = mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").header("Authorization","Bearer "+capability)
                .header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
    }
    private Map<String,?> request() { return Map.of("warehouseId",warehouse,"productIds",products); }
    private long id(String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private String erpState() {
        return jdbc.sql("SELECT jsonb_build_object('stock',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stock_id) FROM stock s),'raw',(SELECT jsonb_agg(to_jsonb(r) ORDER BY raw_material_lot_id) FROM raw_material_lots r),'po',(SELECT count(*) FROM purchase_orders),'poi',(SELECT count(*) FROM purchase_order_items))::text")
                .query(String.class).single();
    }
    private static ActorAuthenticationToken human() {
        return human(1L);
    }
    private static ActorAuthenticationToken human(long userId) {
        return new ActorAuthenticationToken(new HumanActor("https://mulino-auth-test.example/","auth0|plan",userId,"계획 검토자","MANAGER",Set.of("erp:read","work:write")));
    }
    private static ActorAuthenticationToken worker() {
        return new ActorAuthenticationToken(new ServiceActor("https://mulino-auth-test.example/","test-worker@clients","test-worker",Set.of("worker:dispatch")));
    }
}
