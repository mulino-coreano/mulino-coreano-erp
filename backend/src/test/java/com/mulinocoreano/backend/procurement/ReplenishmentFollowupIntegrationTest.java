package com.mulinocoreano.backend.procurement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mulinocoreano.backend.execution.*;
import com.mulinocoreano.backend.interfacepackage.*;
import com.mulinocoreano.backend.planning.*;
import com.mulinocoreano.backend.security.HumanActor;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=followup_core_it",
            "spring.flyway.clean-disabled=false",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
            "spring.datasource.hikari.schema=followup_core_it",
            "spring.main.allow-bean-definition-overriding=true"
        })
@AutoConfigureMockMvc
class ReplenishmentFollowupIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired PlanPersistenceService plans;
    @Autowired com.mulinocoreano.backend.followup.ReplenishmentFollowupService followups;
    @Autowired com.mulinocoreano.backend.followup.ReplenishmentFollowupRepository followupReads;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    long managerId, operatorId, caseId, workId, warehouse;
    PlanDto plan;
    RunExecutionService.Claim claim;
    long originalOrders;

    @TestConfiguration
    static class Time {
        @Bean("planningClock")
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("followup_core_it");
        flyway.clean();
        flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);
        managerId = user("manager", "MANAGER");
        operatorId = user("operator", "OPERATOR");
        warehouse =
                jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'")
                        .query(Long.class)
                        .single();
        var products =
                jdbc.sql(
                                "SELECT product_id FROM products WHERE sku IN"
                                        + " ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                        .query(Long.class)
                        .list();
        caseId =
                jdbc.sql(
                                "INSERT INTO"
                                    + " cases(case_ref,title,objective,intent_type,opened_by_user_id)"
                                    + " VALUES('CASE-PURCHASE','구매 시험','완제품 재보충','ACT',:user)"
                                    + " RETURNING case_id")
                        .param("user", operatorId)
                        .query(Long.class)
                        .single();
        long sc = work("WI-SUPPLY", "SUPPLY_CHAIN");
        runs.createRun(
                new CreateRunRequest("SUPPLY_CHAIN", "CASE-PURCHASE", "WI-SUPPLY", "CODEX"), null);
        var supply = execution.claim("supply-worker").orElseThrow();
        plan =
                plans.calculate(
                        "CASE-PURCHASE",
                        new PlanRequest(warehouse, products, 30),
                        "plan-initial",
                        supply.capabilityToken());
        execution.finish(
                supply.runRef(), "supply-worker", supply.leaseToken(), "DONE", "계획 저장 확인", null);
        workId = work("WI-PURCHASE", "PROCUREMENT");
        runs.createRun(
                new CreateRunRequest("PROCUREMENT", "CASE-PURCHASE", "WI-PURCHASE", "CODEX"), null);
        claim = execution.claim("purchase-worker").orElseThrow();
        originalOrders = count("purchase_orders");
    }


    void applied() throws Exception {
        var proposal=propose();
        decide(proposal.path("approvalId").asLong(),decisionBody(proposal,"APPROVE"),"followup-approve",managerId,"MANAGER",200);
        tx.executeWithoutResult(ignored -> followups.ensureForVerifiedCompletion(caseId,workId));
    }
    void overdue() {
        jdbc.sql("UPDATE purchase_order_items SET expected_delivery_date='2026-09-03' WHERE purchase_order_id IN (SELECT purchase_order_id FROM purchase_orders WHERE purchase_application_id IS NOT NULL)").update();
        jdbc.sql("UPDATE replenishment_followups SET due_at='2026-09-03T15:00:00Z'").update();
    }
    @Test void appliedCreationIsAtomicIdempotentAndTyped() throws Exception {
        long parent=work("WI-PARENT","ORCHESTRATOR");
        jdbc.sql("UPDATE work_items SET metadata='{\"parentWorkItemRef\":\"WI-PARENT\"}' WHERE work_item_id=:id").param("id",workId).update();
        applied();
        assertThat(followupReads.hasResponsibility(parent,caseId)).isTrue();
        var before=followupReads.forCase(caseId).getFirst();
        assertThat(before.agentKey()).isEqualTo("ORCHESTRATOR");
        assertThat(before.serverManaged()).isTrue();
        assertThat(before.parentWorkItemRef()).isEqualTo("WI-PARENT");
        assertThat(before.dueAt()).isNotNull();
        assertThat(before.attentionRequestId()).isNull();
        long events=count("events"),runsBefore=count("runs");
        tx.executeWithoutResult(ignored -> followups.ensureForVerifiedCompletion(caseId,workId));
        assertThat(count("replenishment_followups")).isEqualTo(1);
        assertThat(count("events")).isEqualTo(events);
        assertThat(count("runs")).isEqualTo(runsBefore);
        assertThat(jdbc.sql("SELECT status::text FROM cases WHERE case_id=:id").param("id",caseId).query(String.class).single()).isEqualTo("WAITING");
        assertThatThrownBy(() -> jdbc.sql("UPDATE replenishment_followups SET source_work_item_id=work_item_id").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM replenishment_followups").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE replenishment_followups").update()).isInstanceOf(Exception.class);
    }
    @Test void transactionRollbackRemovesNewResponsibility() throws Exception {
        var proposal=propose(); decide(proposal.path("approvalId").asLong(),decisionBody(proposal,"APPROVE"),"rollback-approve",managerId,"MANAGER",200);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {followups.ensureForVerifiedCompletion(caseId,workId); throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(count("replenishment_followups")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM work_items WHERE title='입고 및 생산/재고 후속 확인'").query(Long.class).single()).isZero();
        assertThatThrownBy(() -> followups.ensureForVerifiedCompletion(caseId,workId)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void overdueRepeatsAreQuietAndHumanAnswerSurvivesLateSplitLotReceipt() throws Exception {
        applied(); overdue();
        assertThat(followups.sweepDue()).hasSize(1);
        long events=count("events"),waits=count("waiting_conditions");
        var initial=followupReads.forCase(caseId).getFirst();
        assertThat(initial.attentionRequestId()).isNotNull();
        assertThat(followups.sweepDue()).isEmpty();
        assertThat(count("events")).isEqualTo(events);
        assertThat(count("waiting_conditions")).isEqualTo(waits);
        jdbc.sql("UPDATE attention_requests SET status='ANSWERED',answer_text='납품 확인 진행',answer_scope='THIS_CASE',resolved_by_user_id=:user,resolved_at=CURRENT_TIMESTAMP WHERE attention_request_id=:id").param("user",managerId).param("id",initial.attentionRequestId()).update();
        receiveAll("RELEASED",true);
        assertThat(followups.sweepDue()).hasSize(1);
        var result=followupReads.forCase(caseId).getFirst();
        assertThat(result.dueAt()).isNull();
        assertThat(result.observationStatus()).isIn("PRODUCTION_REVIEW_REQUIRED","STOCK_REVIEW_REQUIRED");
        assertThat(result.attentionRequestId()).isEqualTo(initial.attentionRequestId());
        assertThat(result.attentionStatus()).isEqualTo("ANSWERED");
        assertThat(jdbc.sql("SELECT answer_text FROM attention_requests WHERE attention_request_id=:id").param("id",initial.attentionRequestId()).query(String.class).single()).isEqualTo("납품 확인 진행");
        assertThat(followups.sweepDue()).isEmpty();
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref=:ref").param("ref",result.workItemRef()).query(String.class).single()).isEqualTo("WAITING");
    }
    @Test void holdReceiptsAndLotIdentityMismatchNeverComplete() throws Exception {
        applied(); overdue(); receiveAll("HOLD",false);
        followups.sweepDue();
        assertThat(followupReads.forCase(caseId).getFirst().observationStatus()).isEqualTo("RECEIPT_REVIEW_REQUIRED");
        jdbc.sql("UPDATE inbound SET status='RELEASED',status_reason='검사',status_decided_by=:user,status_decided_at=CURRENT_TIMESTAMP WHERE purchase_order_item_id IN (SELECT i.purchase_order_item_id FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL)").param("user",managerId).update();
        jdbc.sql("UPDATE raw_material_lots SET raw_material_id=(SELECT max(raw_material_id) FROM raw_materials) WHERE lot_number LIKE 'FU-%'").update();
        followups.sweepDue();
        assertThat(followupReads.forCase(caseId).getFirst().dueAt()).isNotNull();
    }
    @Test void noPurchaseUsesActualZeroProductionPlanWithoutInventedDate() throws Exception {
        jdbc.sql("UPDATE orders SET status='PENDING'").update();
        work("WI-SUPPLY-EMPTY","SUPPLY_CHAIN");
        runs.createRun(new CreateRunRequest("SUPPLY_CHAIN","CASE-PURCHASE","WI-SUPPLY-EMPTY","CODEX"),null);
        var supply=execution.claim("empty-supply-worker").orElseThrow();
        var products=jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        plan=plans.calculate("CASE-PURCHASE",new PlanRequest(warehouse,products,30),"empty-plan",supply.capabilityToken());
        execution.finish(supply.runRef(),"empty-supply-worker",supply.leaseToken(),"DONE","구매 불필요 계획 확인",null);
        assertThat(propose().path("status").asText()).isEqualTo("NO_PURCHASE_REQUIRED");
        tx.executeWithoutResult(ignored -> followups.ensureForVerifiedCompletion(caseId,workId));
        var result=followupReads.forCase(caseId).getFirst();
        assertThat(result.observation().path("productionRequired").asBoolean()).isFalse();
        assertThat(result.observationStatus()).isEqualTo("STOCK_REVIEW_REQUIRED");
        assertThat(result.dueAt()).isNull();
        assertThat(result.parentWorkItemRef()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM waiting_conditions WHERE work_item_id=(SELECT work_item_id FROM replenishment_followups)").query(Long.class).single()).isZero();
        assertThat(count("purchase_applications")).isZero();
    }
    @Test void earliestExpiredLineStaysActiveThenAdvancesToFutureLine() throws Exception {
        applied(); overdue();
        jdbc.sql("UPDATE purchase_order_items SET expected_delivery_date='2026-09-07' WHERE purchase_order_item_id=(SELECT max(i.purchase_order_item_id) FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL)").update();
        followups.sweepDue();
        var initial=followupReads.forCase(caseId).getFirst();
        assertThat(initial.dueAt().toInstant()).isEqualTo(Instant.parse("2026-09-03T15:00:00Z"));
        receiveAll("RELEASED",false);
        jdbc.sql("DELETE FROM raw_material_lots WHERE inbound_id IN (SELECT n.inbound_id FROM inbound n JOIN purchase_order_items i USING(purchase_order_item_id) WHERE i.expected_delivery_date='2026-09-07')").update();
        jdbc.sql("DELETE FROM inbound WHERE purchase_order_item_id IN (SELECT purchase_order_item_id FROM purchase_order_items WHERE expected_delivery_date='2026-09-07')").update();
        followups.sweepDue();
        var result=followupReads.forCase(caseId).getFirst();
        assertThat(result.dueAt().toInstant()).isEqualTo(Instant.parse("2026-09-07T15:00:00Z"));
        assertThat(result.observationStatus()).isEqualTo("AWAITING_RECEIPT");
        assertThat(result.attentionRequestId()).isEqualTo(initial.attentionRequestId());
        assertThat(followups.sweepDue()).isEmpty();
    }
    @Test void simultaneousSweepsEmitOneChangeAndClosedCaseIsNotResurrected() throws Exception {
        applied(); overdue();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> {start.await(); return followups.sweepDue();});
            var second=pool.submit(() -> {start.await(); return followups.sweepDue();});
            start.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS).size()+second.get(20,TimeUnit.SECONDS).size()).isEqualTo(1);
        }
        var result=followupReads.forCase(caseId).getFirst();
        jdbc.sql("UPDATE cases SET status='CLOSED',resolved_at=CURRENT_TIMESTAMP WHERE case_id=:id").param("id",caseId).update();
        receiveAll("RELEASED",true);
        assertThat(followups.sweepDue()).isEmpty();
        assertThat(followupReads.forCase(caseId).getFirst().observation()).isEqualTo(result.observation());
    }
    @Test void forgedOrCrossCaseParentCannotCreateResponsibility() throws Exception {
        var proposal=propose(); decide(proposal.path("approvalId").asLong(),decisionBody(proposal,"APPROVE"),"parent-approve",managerId,"MANAGER",200);
        jdbc.sql("UPDATE work_items SET metadata='{\"parentWorkItemRef\":\"WI-SUPPLY\"}' WHERE work_item_id=:id").param("id",workId).update();
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> followups.ensureForVerifiedCompletion(caseId,workId))).hasMessageContaining("FOLLOWUP_PARENT_INVALID");
        assertThat(count("replenishment_followups")).isZero();
    }
    void receiveAll(String status,boolean split) {
        var lines=jdbc.sql("SELECT i.purchase_order_item_id,i.raw_material_id,i.quantity,p.supplier_id,p.warehouse_id FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL ORDER BY i.purchase_order_item_id").query().listOfRows();
        for(var line:lines) {
            long inbound=jdbc.sql("INSERT INTO inbound(raw_material_id,supplier_id,warehouse_id,purchase_order_item_id,quantity,inbound_date,status,status_reason,status_decided_by,status_decided_at) VALUES(:material,:supplier,:warehouse,:item,:quantity,'2026-09-05',CAST(:status AS inbound_status),:reason,:user,:decided) RETURNING inbound_id")
                .param("material",line.get("raw_material_id")).param("supplier",line.get("supplier_id")).param("warehouse",line.get("warehouse_id")).param("item",line.get("purchase_order_item_id")).param("quantity",line.get("quantity")).param("status",status).param("reason",status.equals("HOLD")?null:"검사 완료").param("user",status.equals("HOLD")?null:managerId).param("decided",status.equals("HOLD")?null:LocalDateTime.now()).query(Long.class).single();
            var quantity=(java.math.BigDecimal)line.get("quantity");
            for(int n=0;n<(split?2:1);n++)jdbc.sql("INSERT INTO raw_material_lots(raw_material_id,inbound_id,lot_number,quantity,remaining_quantity) VALUES(:material,:inbound,:lot,:quantity,:quantity)")
                .param("material",line.get("raw_material_id")).param("inbound",inbound).param("lot","FU-"+inbound+"-"+n).param("quantity",split?quantity.divide(java.math.BigDecimal.TWO):quantity).update();
        }
    }
    JsonNode propose() throws Exception {
        var result =
                mvc.perform(
                                post("/api/v1/plans/{ref}/purchase-proposal", plan.ref())
                                        .header(
                                                "Authorization",
                                                "Bearer " + claim.capabilityToken())
                                        .header("Idempotency-Key", "proposal-initial")
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isOk())
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    String decisionBody(JsonNode proposal, String decision) throws Exception {
        return mapper.writeValueAsString(
                Map.of(
                        "decision",
                        decision,
                        "expectedVersion",
                        proposal.path("version").asInt(),
                        "proposalHash",
                        proposal.path("proposalHash").asText(),
                        "reason",
                        "계획의 수량과 공급 조건 검토"));
    }

    JsonNode decide(long id, String body, String key, long user, String role, int status)
            throws Exception {
        var actor =
                new HumanActor(
                        "https://fixture.example/",
                        "subject-" + user,
                        user,
                        "시험 사용자",
                        role,
                        Set.of("erp:read", "procurement:decide"));
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        actor,
                        null,
                        actor.capabilities().stream().map(SimpleGrantedAuthority::new).toList());
        var result =
                mvc.perform(
                                post("/api/v1/approvals/{id}/decision", id)
                                        .with(authentication(auth))
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().is(status))
                        .andReturn();
        return result.getResponse().getContentAsString().isBlank()
                ? mapper.createObjectNode()
                : mapper.readTree(result.getResponse().getContentAsString());
    }

    long user(String name, String role) {
        return jdbc.sql(
                        "INSERT INTO users(name,email,password,role)"
                            + " VALUES(:name,:email,'test-only',CAST(:role AS user_role)) RETURNING"
                            + " user_id")
                .param("name", name)
                .param("email", name + "@purchase.test")
                .param("role", role)
                .query(Long.class)
                .single();
    }

    long work(String ref, String role) {
        return jdbc.sql(
                        "INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id)"
                            + " SELECT :ref,:case,:ref,agent_id FROM agents WHERE agent_key=:role"
                            + " RETURNING work_item_id")
                .param("ref", ref)
                .param("case", caseId)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
