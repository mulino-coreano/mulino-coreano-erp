package com.mulinocoreano.backend.planning;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 목표 2·3·4: 인간이 계획을 계산하되 ERP 수량이나 발주를 변경하지 않는다. */
@SpringBootTest(properties={"spring.flyway.schemas=human_planning_it", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=human_planning_it", "spring.main.allow-bean-definition-overriding=true", "mulino.local-auth.service-secret=local-test-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HumanPlanningIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired com.mulinocoreano.backend.interfacepackage.RunService runs;
    @Autowired ObjectMapper mapper;
    long warehouse;
    List<Long> products;
    String caseRef;
    @TestConfiguration
    static class Time {
        @Bean("planningClock") @Primary
        Clock clock() {return Clock.fixed(Instant.parse("2026-09-04T16:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @BeforeEach
    void setup() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("human_planning_it");
        flyway.clean(); flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);
        warehouse=jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        products=jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        var r=mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER")
                .contentType("application/json").content("{\"objective\":\"replenish\"}"))
                .andExpect(status().isOk()).andReturn();
        caseRef=mapper.readTree(r.getResponse().getContentAsString()).get("caseRef").asString();
    }
    @Test
    void humanCalculatesSharedBomMoqAndVersionsWithoutWritingErp() throws Exception {
        String before=erp();
        var first=calculate("MANAGER","first",200);
        assertThat(first.path("result").path("status").asString()).isEqualTo("READY");
        assertThat(first.path("result").path("totalAmount").decimalValue()).isEqualByComparingTo("16500");
        assertThat(calculate("MANAGER","first",200)).isEqualTo(first);
        var second=calculate("OPERATOR","second",200);
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(erp()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans WHERE created_by_work_item_id IS NOT NULL").query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/plans/"+first.path("ref").asString()).header("X-Mulino-Local-Role","VIEWER"))
                .andExpect(status().isOk());
    }
    @Test
    void rejectsViewerAndReusedKeyWithDifferentScope() throws Exception {
        calculate("VIEWER","denied",403);
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single()).isZero();
        calculate("MANAGER","fixed",200);
        mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").header("X-Mulino-Local-Role","MANAGER")
                .header("Idempotency-Key","fixed").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"productIds",products,"horizonDays",20))))
                .andExpect(status().isConflict());
    }
    @Test
    void shortHistoryRequiresAttentionWithoutPurchase() throws Exception {
        jdbc.sql("UPDATE planning_policies SET history_start_date='2026-08-25'").update();
        var result=calculate("MANAGER","short",200);
        assertThat(result.path("result").path("status").asString()).isEqualTo("NEEDS_ATTENTION");
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE status='OPEN'").query(Long.class).single()).isEqualTo(1);
    }
    @Test
    void physicalMismatchCannotProduceUsablePlan() throws Exception {
        jdbc.sql("UPDATE stock SET quantity=quantity+1").update();
        calculate("OPERATOR","mismatch",409);
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM attention_requests WHERE status='OPEN'").query(Long.class).single()).isEqualTo(1);
    }
    @Test
    void concurrentHumanCalculationsReplayOnePlan() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->calculate("MANAGER","race",200));
            var b=pool.submit(()->calculate("MANAGER","race",200));
            assertThat(a.get()).isEqualTo(b.get());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single()).isEqualTo(1);
    }
    @Test
    void agentPlansOnlyItsCaseAndCompletesOnlyAfterReadyAttempt() throws Exception {
        long caseId=jdbc.sql("SELECT case_id FROM cases WHERE case_ref=:ref").param("ref",caseRef).query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT 'WI-SUPPLY',:id,'supply',agent_id FROM agents WHERE agent_key='SUPPLY_CHAIN'").param("id",caseId).update();
        runs.createRun(new com.mulinocoreano.backend.interfacepackage.CreateRunRequest("SUPPLY_CHAIN",caseRef,"WI-SUPPLY","CODEX"),null);
        var claimResult=mvc.perform(post("/api/v1/internal/runs/claim").header("X-Mulino-Local-Service","local-test-secret")
                .contentType("application/json").content("{\"workerId\":\"supply-worker\"}"))
                .andExpect(status().isOk()).andReturn();
        var claim=mapper.readTree(claimResult.getResponse().getContentAsString());
        var finish=Map.of("runRef",claim.path("runRef").asString(),"workerId","supply-worker",
                "leaseToken",claim.path("leaseToken").asString(),"outcome","DONE","summary","ready");
        mvc.perform(post("/api/v1/internal/runs/finish").header("X-Mulino-Local-Service","local-test-secret")
                .contentType("application/json").content(mapper.writeValueAsString(finish))).andExpect(status().isConflict());
        String capability="Bearer "+claim.path("capabilityToken").asString();
        mvc.perform(post("/api/v1/cases/CASE-other/plans").header("Authorization",capability)
                .header("Idempotency-Key","wrong").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"productIds",products))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").header("Authorization",capability)
                .header("Idempotency-Key","agent").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"productIds",products))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/internal/runs/finish").header("X-Mulino-Local-Service","local-test-secret")
                .contentType("application/json").content(mapper.writeValueAsString(finish))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref='WI-SUPPLY'").query(String.class).single()).isEqualTo("DONE");
    }

    private JsonNode calculate(String role,String key,int status) throws Exception {
        var r=mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").header("X-Mulino-Local-Role",role)
                .header("Idempotency-Key",key).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("warehouseId",warehouse,"productIds",products))))
                .andExpect(status().is(status)).andReturn();
        String body=r.getResponse().getContentAsString();
        return body.isEmpty()?null:mapper.readTree(body);
    }
    private String erp() {
        return jdbc.sql("SELECT jsonb_build_object('stock',(SELECT sum(quantity) FROM stock),'raw',(SELECT sum(remaining_quantity) FROM raw_material_lots),'po',(SELECT count(*) FROM purchase_orders))::text").query(String.class).single();
    }
}
