package com.mulinocoreano.backend.planning;

import com.zaxxer.hikari.HikariDataSource;
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
import com.mulinocoreano.backend.security.LocalHumanRequest;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 목표 2·3·4: 인간이 계획을 계산하되 ERP 수량이나 발주를 변경하지 않는다. */
@SpringBootTest(properties={"spring.flyway.schemas=human_planning_it", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=human_planning_it", "spring.main.allow-bean-definition-overriding=true", "mulino.local-auth.service-secret=local-test-secret",
        LocalHumanRequest.SECRET_PROPERTY})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class HumanPlanningIntegrationTest {
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired com.mulinocoreano.backend.interfacepackage.RunService runs;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
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
        // Recreated enum types get new OIDs. A pooled connection that server-prepared a plan
        // query in an earlier test would fail it with "cached plan must not change result type".
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        ReplenishmentDemoFixture.load(jdbc);
        warehouse=jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        products=jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        caseRef=createCase("replenish");
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
        mvc.perform(get("/api/v1/plans/"+first.path("ref").asString()).with(LocalHumanRequest.as("VIEWER")))
                .andExpect(status().isOk());
    }
    @Test
    void replayRetainsExactSixDecimalPlanEvidenceAndLegacyRequestHash() throws Exception {
        var idempotency=new com.mulinocoreano.backend.idempotency.RequestIdempotency(jdbc,mapper);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        record Input(long warehouseId,int horizonDays) {}
        var input=new Input(warehouse,30);
        var quantity=new java.math.BigDecimal("123456789012.123456");
        var requestText=mapper.writeValueAsString(input);
        var expectedHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(requestText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var first=transaction.execute(status -> idempotency.executeJson("plans:precision:human:1","exact-replay",input,
            () -> Map.of("ref","PLAN-EXACT","result",Map.of("purchaseQuantity",quantity))));
        var replay=transaction.execute(status -> idempotency.executeJson("plans:precision:human:1","exact-replay",input,
            () -> { throw new AssertionError("A replay cannot calculate another plan"); }));
        assertThat(first.path("result").path("purchaseQuantity").decimalValue()).isEqualByComparingTo(quantity);
        assertThat(replay.path("result").path("purchaseQuantity").decimalValue()).isEqualByComparingTo(quantity);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.sql("SELECT request_hash FROM request_idempotency WHERE scope='plans:precision:human:1' AND request_key='exact-replay'").query(String.class).single()).isEqualTo(expectedHash);
    }
    @Test
    void rejectsViewerAndReusedKeyWithDifferentScope() throws Exception {
        calculate("VIEWER","denied",403);
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single()).isZero();
        calculate("MANAGER","fixed",200);
        mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").with(LocalHumanRequest.as("MANAGER"))
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
                .contentType("application/json").content("{\"workerId\":\"supply-worker\",\"runtime\":\"CODEX\"}"))
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
    @Test
    void concurrentRetriesOfOneRequestAllReturnTheStoredPlan() throws Exception {
        var replies=burst(8,i->"same-request");
        assertThat(replies).extracting(Reply::status).containsOnly(200);
        assertThat(replies).extracting(Reply::body).containsOnly(replies.getFirst().body());
        assertThat(replies.getFirst().body().path("result").path("totalAmount").decimalValue()).isEqualByComparingTo("16500");
        assertThat(jdbc.sql("SELECT count(*) FROM replenishment_plans").query(Long.class).single()).isEqualTo(1);
    }
    @Test
    void concurrentNewRequestsEachStoreTheNextVersion() throws Exception {
        var replies=burst(6,i->"request-"+i);
        assertThat(replies).extracting(Reply::status).containsOnly(200);
        assertThat(replies).extracting(reply->reply.body().path("version").asInt()).containsExactlyInAnyOrder(1,2,3,4,5,6);
        assertThat(replies).allSatisfy(reply->assertThat(reply.body().path("result").path("totalAmount").decimalValue())
                .isEqualByComparingTo("16500"));
    }
    @Test
    void calculationWaitingOnPlanningDataChangeUsesTheCommittedChange() throws Exception {
        try(var pool=Executors.newSingleThreadExecutor(); var writer=dataSource.getConnection()) {
            writer.setAutoCommit(false);
            writer.createStatement().executeUpdate(DOUBLE_SUPPLIER_PRICES);
            var plan=pool.submit(()->calculate("MANAGER","after-change",200));
            awaitBlockedBy(writer);
            writer.commit();
            assertThat(plan.get().path("result").path("totalAmount").decimalValue()).isEqualByComparingTo("33000");
        }
    }
    @Test
    void planningDataChangeCannotCommitWhileCalculationRuns() throws Exception {
        try(var pool=Executors.newSingleThreadExecutor(); var caseEditor=dataSource.getConnection()) {
            // Another Case writer pauses the calculation after it has started reading planning data.
            caseEditor.setAutoCommit(false);
            var lockCase=caseEditor.prepareStatement("SELECT case_id FROM cases WHERE case_ref=? FOR UPDATE");
            lockCase.setString(1,caseRef);
            lockCase.execute();
            var plan=pool.submit(()->calculate("MANAGER","before-change",200));
            awaitBlockedBy(caseEditor);
            try(var writer=dataSource.getConnection()) {
                writer.setAutoCommit(false);
                writer.createStatement().execute("SET LOCAL lock_timeout='500ms'");
                assertThatThrownBy(()->writer.createStatement().executeUpdate(DOUBLE_SUPPLIER_PRICES))
                        .isInstanceOf(SQLException.class);
                writer.rollback();
            }
            caseEditor.rollback();
            assertThat(plan.get().path("result").path("totalAmount").decimalValue()).isEqualByComparingTo("16500");
        }
        jdbc.sql(DOUBLE_SUPPLIER_PRICES).update();
        assertThat(calculate("MANAGER","after-change",200).path("result").path("totalAmount").decimalValue())
                .isEqualByComparingTo("33000");
    }
    @Test
    void rejectedCalculationDoesNotBindCaseToRequestedWarehouse() throws Exception {
        long unconfigured=jdbc.sql("INSERT INTO warehouses(name,type) VALUES ('정책 없는 창고','AMBIENT') RETURNING warehouse_id")
                .query(Long.class).single();
        var wrongWarehouse=Map.<String,Object>of("warehouseId",unconfigured,"productIds",products);
        var rejected=request(caseRef,"MANAGER","wrong-warehouse",wrongWarehouse);
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(request(caseRef,"MANAGER","wrong-warehouse",wrongWarehouse)).isEqualTo(rejected);
        assertThat(openAttentions()).isEqualTo(1);
        assertThat(request(caseRef,"MANAGER","right-warehouse",Map.of("warehouseId",warehouse,"productIds",products)).status())
                .isEqualTo(200);
    }
    @Test
    void rejectedCalculationLeavesWarehouseFreeForAnotherCase() throws Exception {
        long dough=jdbc.sql("SELECT product_id FROM products WHERE sku='DEMO-DOUGH'").query(Long.class).single();
        assertThat(request(caseRef,"MANAGER","semi-finished",Map.of("warehouseId",warehouse,"productIds",List.of(dough))).status())
                .isEqualTo(409);
        assertThat(openAttentions()).isEqualTo(1);
        String otherCase=createCase("replenish other");
        assertThat(request(otherCase,"MANAGER","finished",Map.of("warehouseId",warehouse,"productIds",products)).status())
                .isEqualTo(200);
    }
    private static final String DOUBLE_SUPPLIER_PRICES="UPDATE supplier_material_terms SET unit_price=unit_price*2";
    record Reply(int status,JsonNode body) {}
    private Reply request(String ref,String role,String key,Map<String,Object> body) throws Exception {
        var r=mvc.perform(post("/api/v1/cases/"+ref+"/plans").with(LocalHumanRequest.as(role))
                .header("Idempotency-Key",key).contentType("application/json")
                .content(mapper.writeValueAsString(body))).andReturn();
        String text=r.getResponse().getContentAsString();
        return new Reply(r.getResponse().getStatus(),text.isEmpty()?null:mapper.readTree(text));
    }
    private List<Reply> burst(int size,IntFunction<String> key) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(size)) {
            var futures=new ArrayList<Future<Reply>>();
            for(int i=0;i<size;i++) {
                String requestKey=key.apply(i);
                futures.add(pool.submit(()->{start.await();
                    return request(caseRef,"MANAGER",requestKey,Map.of("warehouseId",warehouse,"productIds",products));}));
            }
            start.countDown();
            var replies=new ArrayList<Reply>();
            for(var future:futures) replies.add(future.get());
            return replies;
        }
    }
    private void awaitBlockedBy(Connection holder) throws Exception {
        var pidQuery=holder.createStatement().executeQuery("SELECT pg_backend_pid()");
        pidQuery.next();
        int pid=pidQuery.getInt(1);
        for(int i=0;i<200;i++) {
            if(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid))")
                    .param("pid",pid).query(Long.class).single()>0) return;
            Thread.sleep(50);
        }
        fail("calculation did not wait for the open transaction");
    }
    private long openAttentions() {
        return jdbc.sql("SELECT count(*) FROM attention_requests WHERE status='OPEN'").query(Long.class).single();
    }
    private String createCase(String objective) throws Exception {
        var r=mvc.perform(post("/api/v1/cases").with(LocalHumanRequest.as("MANAGER"))
                .contentType("application/json").content(mapper.writeValueAsString(Map.of("objective",objective))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString()).get("caseRef").asString();
    }
    private JsonNode calculate(String role,String key,int status) throws Exception {
        var r=mvc.perform(post("/api/v1/cases/"+caseRef+"/plans").with(LocalHumanRequest.as(role))
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
