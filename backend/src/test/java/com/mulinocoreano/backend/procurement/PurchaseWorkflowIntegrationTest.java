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
            "spring.flyway.schemas=purchase_workflow_it",
            "spring.flyway.clean-disabled=false",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
            "spring.datasource.hikari.schema=purchase_workflow_it",
            "spring.main.allow-bean-definition-overriding=true"
        })
@AutoConfigureMockMvc
class PurchaseWorkflowIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RunService runs;
    @Autowired DispatcherService dispatcher;
    @Autowired CaseOverviewService overviews;
    @Autowired AttentionAnswerService answers;
    @Autowired InterfaceReadRepository interfaceReads;
    @Autowired RunExecutionService execution;
    @MockitoSpyBean PlanPersistenceService plans;
    @Autowired AgentQueryService agentQueries;
    @MockitoSpyBean AgentPurchasingReads scopedPurchasing;
    @Autowired CanonicalJson exactJson;
    @MockitoSpyBean ReplenishmentCalculator calculator;
    @MockitoSpyBean PurchasePlanRepository purchasePlans;
    long managerId, operatorId, caseId, workId, warehouse;
    PlanDto plan;
    RunExecutionService.Claim claim;
    long originalOrders;

    @Test
    void planReadRechecksTheLeaseBeforeReturningItsEvidence() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_ref=:ref")
                    .param("ref", claim.runRef()).update();
            return result;
        }).when(plans).get(plan.ref());
        assertThatThrownBy(() -> agentQueries.plan(claim.capabilityToken(), "PROCUREMENT", "CASE-PURCHASE", plan.ref()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("STALE_LEASE");
    }

    @Test
    void scopedMaterialReturnsOnlyCurrentLinkedFactsAndPreservesPlan() throws Exception {
        long material = jdbc.sql("SELECT min(raw_material_id) FROM supplier_material_terms").query(Long.class).single();
        String before = exactJson.write(plans.get(plan.ref()));
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=123456789012.123456 WHERE raw_material_id=:id")
                .param("id", material).update();
        var result = agentGet("materials", material, claim, 200);
        assertThat(result.path("caseRef").asText()).isEqualTo("CASE-PURCHASE");
        assertThat(result.path("planRef").asText()).isEqualTo(plan.ref());
        assertThat(result.path("asOf").asText()).isEqualTo("2026-09-05");
        assertThat(result.path("material").path("raw_material_id").asLong()).isEqualTo(material);
        assertThat(result.path("supplierTerms")).isNotEmpty();
        for (var term : result.path("supplierTerms"))
            assertThat(term.path("unitPrice").decimalValue()).isEqualByComparingTo("123456789012.123456");
        for (var supply : result.path("supply"))
            assertThat(supply.path("item").path("id").asLong()).isEqualTo(material);
        assertThat(exactJson.write(plans.get(plan.ref()))).isEqualTo(before);
        agentGet("materials", Long.MAX_VALUE, claim, 404);
        agentGet("materials", 0, claim, 400);
        jdbc.sql("DELETE FROM planning_policies WHERE warehouse_id=:id").param("id", warehouse).update();
        agentGet("materials", material, claim, 409);
        assertThat(count("attention_requests")).isZero();
    }

    @Test
    void scopedPurchaseRequiresLatestEvidenceAndPreservesLargeIdsAndDecimals() throws Exception {
        long evidenced = java.util.stream.StreamSupport.stream(plan.sourceSnapshot().path("sourceFacts").spliterator(), false).filter(f -> f.path("sourceRef").asText().startsWith("purchase_order_items:")).findFirst().orElseThrow().path("values").path("purchase_order_id").asLong();
        agentGet("purchase-orders", evidenced, claim, 200);
        long unlinked = jdbc.sql("INSERT INTO purchase_orders(purchase_order_id,supplier_id,created_by,order_date,status) SELECT 9007199254740993,supplier_id,:user,CURRENT_DATE,'ORDERED' FROM purchase_orders LIMIT 1 RETURNING purchase_order_id")
                .param("user", managerId).query(Long.class).single();
        agentGet("purchase-orders", unlinked, claim, 404);
        jdbc.sql("INSERT INTO replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash,created_by_work_item_id) SELECT 'PLAN-READ-LARGE',case_id,warehouse_id,version+1,as_of,horizon_days,target_date,jsonb_build_object('sourceFacts',jsonb_build_array(jsonb_build_object('sourceRef',:source))),result,source_hash,plan_hash,created_by_work_item_id FROM replenishment_plans WHERE plan_ref=:ref")
                .param("source", "purchase_orders:"+unlinked).param("ref", plan.ref()).update();
        long material = jdbc.sql("SELECT min(raw_material_id) FROM raw_materials").query(Long.class).single();
        jdbc.sql("INSERT INTO purchase_order_items(purchase_order_id,raw_material_id,quantity,unit_price) VALUES(:po,:material,12.345678,123456789012345.123456789)")
                .param("po", unlinked).param("material", material).update();
        var result = agentGet("purchase-orders", unlinked, claim, 200);
        assertThat(result.path("id").asLong()).isEqualTo(unlinked);
        assertThat(result.path("items").get(0).path("baseUnitPrice").decimalValue()).isEqualByComparingTo("123456789012345.123456789");
        agentGet("purchase-orders", evidenced, claim, 404);
        agentGet("purchase-orders", -1, claim, 400);
    }

    @Test
    void purchasingReadsRejectStaleReassignedFinishedAndNonAgentCredentials() throws Exception {
        long material = jdbc.sql("SELECT min(raw_material_id) FROM raw_materials").query(Long.class).single();
        for (String resource : List.of("materials", "purchase-orders")) {
            var human = new HumanActor("https://fixture.example/", "manager", managerId, "Manager", "MANAGER", Set.of("erp:read"));
            var service = new com.mulinocoreano.backend.security.ServiceActor("https://fixture.example/", "worker", "worker", Set.of("worker:dispatch"));
            for (Object actor : List.of(human, service)) {
                var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new SimpleGrantedAuthority("erp:read")));
                mvc.perform(get("/api/v1/agent/"+resource+"/"+material).with(authentication(auth))).andExpect(status().isUnauthorized());
            }
            mvc.perform(get("/api/v1/agent/"+resource+"/"+material).header("Authorization", "Bearer human-or-m2m-jwt"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/agent/"+resource+"/9223372036854775808").header("Authorization", "Bearer "+claim.capabilityToken()))
                    .andExpect(status().isBadRequest());
        }
        jdbc.sql("UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE agent_key='QC') WHERE work_item_id=:id").param("id", workId).update();
        agentGet("materials", material, claim, 409);
        agentGet("purchase-orders", material, claim, 409);
        jdbc.sql("UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE agent_key='PROCUREMENT') WHERE work_item_id=:id").param("id", workId).update();
        jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_ref=:ref").param("ref", claim.runRef()).update();
        agentGet("materials", material, claim, 409);
        agentGet("purchase-orders", material, claim, 409);
        jdbc.sql("UPDATE runs SET status='COMPLETED',finished_at=clock_timestamp() WHERE run_ref=:ref").param("ref", claim.runRef()).update();
        agentGet("materials", material, claim, 409);
    }

    @Test
    void scopedReadsRecheckCapabilityAfterCurrentFactsAreLoaded() {
        long material = jdbc.sql("SELECT min(raw_material_id) FROM raw_materials").query(Long.class).single();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_ref=:ref").param("ref", claim.runRef()).update();
            return result;
        }).when(scopedPurchasing).material(caseId, "CASE-PURCHASE", material);
        assertThatThrownBy(() -> agentQueries.material(claim.capabilityToken(), "PROCUREMENT", "CASE-PURCHASE", material))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("STALE_LEASE");
    }

    @Test
    void foreignCaseWithoutPlanCannotReadPurchasingFacts() throws Exception {
        long material = jdbc.sql("SELECT min(raw_material_id) FROM raw_materials").query(Long.class).single();
        long order = java.util.stream.StreamSupport.stream(plan.sourceSnapshot().path("sourceFacts").spliterator(), false).filter(f -> f.path("sourceRef").asText().startsWith("purchase_order_items:")).findFirst().orElseThrow().path("values").path("purchase_order_id").asLong();
        long foreign = jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES('CASE-FOREIGN-READ','Foreign','Other case','ACT') RETURNING case_id").query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT 'WI-FOREIGN-READ',:id,'Foreign',agent_id FROM agents WHERE agent_key='PROCUREMENT'").param("id", foreign).update();
        runs.createRun(new CreateRunRequest("PROCUREMENT", "CASE-FOREIGN-READ", "WI-FOREIGN-READ", "CODEX"), null);
        var other = execution.claim("foreign-worker").orElseThrow();
        agentGet("materials", material, other, 404);
        agentGet("purchase-orders", order, other, 404);
    }

    @Test
    void scopedOrderRechecksCapabilityAfterProjection() {
        long order = java.util.stream.StreamSupport.stream(plan.sourceSnapshot().path("sourceFacts").spliterator(), false).filter(f -> f.path("sourceRef").asText().startsWith("purchase_order_items:")).findFirst().orElseThrow().path("values").path("purchase_order_id").asLong();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.sql("UPDATE runs SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_ref=:ref").param("ref", claim.runRef()).update();
            return result;
        }).when(scopedPurchasing).order(caseId, order);
        assertThatThrownBy(() -> agentQueries.purchaseOrder(claim.capabilityToken(), "PROCUREMENT", "CASE-PURCHASE", order))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("STALE_LEASE");
    }

    private JsonNode agentGet(String resource, long id, RunExecutionService.Claim token, int statusCode) throws Exception {
        var result = mvc.perform(get("/api/v1/agent/"+resource+"/"+id).header("Authorization", "Bearer "+token.capabilityToken()))
                .andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return result.isEmpty() ? exactJson.readTree("{}") : exactJson.readTree(result);
    }

    @Test
    void approvalWaitingBehindRecalculationMustSeeTheNewPlanVersion() throws Exception {
        JsonNode proposal = propose();
        work("WI-REPLAN", "SUPPLY_CHAIN");
        runs.createRun(
                new CreateRunRequest("SUPPLY_CHAIN", "CASE-PURCHASE", "WI-REPLAN", "CODEX"), null);
        var replanClaim = execution.claim("replan-worker").orElseThrow();
        var products =
                jdbc.sql(
                                "SELECT product_id FROM products WHERE sku IN"
                                    + " ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                        .query(Long.class)
                        .list();
        var calculating = new CountDownLatch(1);
        var releaseCalculation = new CountDownLatch(1);
        var approving = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            if (Thread.currentThread().getName().equals("replan-race")) {
                                calculating.countDown();
                                if (!releaseCalculation.await(10, TimeUnit.SECONDS))
                                    throw new AssertionError("Calculation was not released");
                            }
                            return invocation.callRealMethod();
                        })
                .when(calculator)
                .calculate(any());
        doAnswer(
                        invocation -> {
                            if (Thread.currentThread().getName().equals("approval-race")) {
                                jdbc.sql("SET LOCAL application_name='mulino-approval-race'")
                                        .update();
                                approving.countDown();
                            }
                            return invocation.callRealMethod();
                        })
                .when(purchasePlans)
                .lockSources();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var replan =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("replan-race");
                                return plans.calculate(
                                        "CASE-PURCHASE",
                                        new PlanRequest(warehouse, products, 30),
                                        "new-plan",
                                        replanClaim.capabilityToken());
                            });
            assertThat(calculating.await(10, TimeUnit.SECONDS)).isTrue();
            var decision =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("approval-race");
                                return decisionStatus(
                                        proposal.path("approvalId").asLong(),
                                        decisionBody(proposal, "APPROVE"),
                                        "version-race");
                            });
            try {
                assertThat(approving.await(5, TimeUnit.SECONDS)).isTrue();
                boolean blocked = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !blocked) {
                    blocked =
                            jdbc.sql(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE"
                                                + " application_name='mulino-approval-race' AND"
                                                + " wait_event_type='Lock')")
                                    .query(Boolean.class)
                                    .single();
                    if (!blocked) Thread.sleep(10);
                }
                assertThat(blocked)
                        .as("The approval must actually be waiting on a PostgreSQL lock")
                        .isTrue();
            } finally {
                releaseCalculation.countDown();
            }
            assertThat(replan.get(15, TimeUnit.SECONDS).version()).isEqualTo(plan.version() + 1);
            assertThat(decision.get(15, TimeUnit.SECONDS)).isEqualTo(409);
        } finally {
            releaseCalculation.countDown();
        }
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(count("purchase_applications")).isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM governance_actions WHERE"
                                            + " governance_action_id=:id")
                                .param("id", proposal.path("approvalId").asLong())
                                .query(String.class)
                                .single())
                .isEqualTo("EXPIRED");
    }

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
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("purchase_workflow_it");
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
        work("WI-ORCH", "ORCHESTRATOR");
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
        jdbc.sql("UPDATE work_items SET metadata=coalesce(metadata,'{}'::jsonb) || '{\"parentWorkItemRef\":\"WI-ORCH\"}'::jsonb WHERE work_item_id=:id")
                .param("id", workId).update();
        runs.createRun(
                new CreateRunRequest("PROCUREMENT", "CASE-PURCHASE", "WI-PURCHASE", "CODEX"), null);
        claim = execution.claim("purchase-worker").orElseThrow();
        originalOrders = count("purchase_orders");
    }

    @Test
    void approvalEvidenceKeepsItsImmutablePlanAfterErpAndLatestPlanChange() throws Exception {
        var proposal = propose();
        long id = proposal.path("approvalId").asLong();
        var actor = new HumanActor("https://fixture.example/", "viewer", operatorId,
                "Viewer", "VIEWER", Set.of("erp:read"));
        var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority("erp:read")));
        String path = "/api/v1/approvals/" + id;
        var before = exactJson.readTree(mvc.perform(get(path).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var evidence = before.path("planEvidence");
        assertThat(evidence.path("planRef").asText()).isEqualTo(plan.ref());
        assertThat(evidence.path("result")).isEqualTo(exactJson.readTree(exactJson.write(plan.result())));
        assertThat(evidence.path("supply")).isEqualTo(plan.sourceSnapshot().path("supply"));
        assertThat(evidence.path("sourceRefs")).isNotEmpty();
        assertThat(before.path("noActionConsequence").asText()).contains("목표 재고");
        assertThat(java.util.stream.StreamSupport.stream(evidence.path("supply").spliterator(), false)
                .anyMatch(l -> l.path("projected").asBoolean())).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(evidence.path("supply").spliterator(), false)
                .anyMatch(l -> !l.path("projected").asBoolean())).isTrue();
        String stored = exactJson.write(plans.get(plan.ref()));
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=123456789012.123456").update();
        jdbc.sql("UPDATE stock SET quantity=quantity+999").update();
        jdbc.sql("INSERT INTO replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash,created_by_work_item_id) SELECT 'PLAN-LATER-APPROVAL-READ',case_id,warehouse_id,version+1,as_of,horizon_days,target_date,'{}','{}',source_hash,plan_hash,created_by_work_item_id FROM replenishment_plans WHERE plan_ref=:ref")
                .param("ref", plan.ref()).update();
        var after = exactJson.readTree(mvc.perform(get(path).with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(after).isEqualTo(before);
        assertThat(exactJson.write(plans.get(plan.ref()))).isEqualTo(stored);
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(count("governance_decisions")).isZero();
    }

    @Test
    void proposalWaitsWithoutPurchaseAndManagerAppliesExactlyOnce() throws Exception {
        JsonNode proposal = propose();
        long approvalId = proposal.path("approvalId").asLong();
        assertThat(approvalId).isPositive();
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(
                        jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                                .param("id", workId)
                                .query(String.class)
                                .single())
                .isEqualTo("WAITING");
        assertThat(
                        execution
                                .heartbeat(claim.runRef(), "purchase-worker", claim.leaseToken())
                                .outcome())
                .isEqualTo("WAITING");
        String body = decisionBody(proposal, "APPROVE");
        JsonNode applied = decide(approvalId, body, "approve-once", managerId, "MANAGER", 200);
        assertThat(applied.path("status").asText()).isEqualTo("APPROVED");
        assertThat(count("purchase_orders")).isEqualTo(originalOrders + 1);
        assertThat(count("purchase_applications")).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT sum(line_amount) FROM purchase_order_items WHERE"
                                                + " purchase_quantity IS NOT NULL")
                                .query(java.math.BigDecimal.class)
                                .single())
                .isEqualByComparingTo("16500");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM purchase_orders WHERE"
                                            + " purchase_application_id IS NOT NULL AND"
                                            + " created_by=:user AND tax_invoice_number IS NULL AND"
                                            + " tax_invoice_date IS NULL")
                                .param("user", managerId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(decide(approvalId, body, "approve-once", managerId, "MANAGER", 200))
                .isEqualTo(applied);
        assertThat(count("purchase_applications")).isEqualTo(1);
        var next = execution.claim("verify-worker").orElseThrow();
        assertThat(next.agentKey()).isEqualTo("PROCUREMENT");
        long appliedOrder = jdbc.sql("SELECT purchase_order_id FROM purchase_orders WHERE purchase_application_id IS NOT NULL").query(Long.class).single();
        assertThat(agentGet("purchase-orders", appliedOrder, next, 200).path("caseRef").asText()).isEqualTo("CASE-PURCHASE");
        agentGet("purchase-orders", appliedOrder, claim, 401);
        var purchaseContext = mapper.valueToTree(next.context()).path("purchasing");
        assertThat(purchaseContext.isArray()).isTrue();
        assertThat(purchaseContext.get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(purchaseContext.get(0).path("purchaseOrderIds").size()).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM case_participants WHERE"
                                                + " case_id=:caseId AND user_id=:user")
                                .param("caseId", caseId)
                                .param("user", managerId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        jdbc.sql("UPDATE work_items SET status='WAITING' WHERE work_item_ref='WI-ORCH'").update();
        jdbc.sql("INSERT INTO waiting_conditions(waiting_ref,work_item_id,condition_type,condition_payload,reason) SELECT 'WAIT-PARENT',work_item_id,'DEPENDENCY_DONE','{\"dependentWiRef\":\"WI-PURCHASE\"}','구매 완료 대기' FROM work_items WHERE work_item_ref='WI-ORCH'").update();
        assertThat(
                        execution
                                .finish(
                                        next.runRef(),
                                        "verify-worker",
                                        next.leaseToken(),
                                        "DONE",
                                        "발주 결과 확인",
                                        null)
                                .outcome())
                .isEqualTo("DONE");
        assertThat(
                        jdbc.sql("SELECT status::text FROM cases WHERE case_id=:id")
                                .param("id", caseId)
                                .query(String.class)
                                .single())
                .isEqualTo("WAITING");
        assertThat(count("replenishment_followups")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT parent_work_item_id=(SELECT work_item_id FROM work_items WHERE work_item_ref='WI-ORCH') FROM replenishment_followups").query(Boolean.class).single()).isTrue();
        assertThat(execution.finish(next.runRef(), "verify-worker", next.leaseToken(),
                "DONE", "중복 완료 확인", null).alreadyFinished()).isTrue();
        assertThat(count("replenishment_followups")).isEqualTo(1);
        assertThat(mapper.valueToTree(overviews.overview("CASE-PURCHASE")).path("followups")).hasSize(1);
        assertThat(jdbc.sql("SELECT status::text FROM waiting_conditions WHERE waiting_ref='WAIT-PARENT'").query(String.class).single()).isEqualTo("SATISFIED");
        var parent = execution.claim("parent-worker").orElseThrow();
        assertThat(mapper.valueToTree(parent.context()).path("followups")).hasSize(1);
        assertThat(execution.finish(parent.runRef(), "parent-worker", parent.leaseToken(),
                "DONE", "서버 후속 책임 확인", null).outcome()).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT status::text FROM cases WHERE case_id=:id").param("id", caseId)
                .query(String.class).single()).isEqualTo("WAITING");
    }

    @Test
    void changedInputsExpireProposalAndNeverCreatePurchase() throws Exception {
        JsonNode proposal = propose();
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=unit_price+1").update();
        JsonNode expired =
                decide(
                        proposal.path("approvalId").asLong(),
                        decisionBody(proposal, "APPROVE"),
                        "stale",
                        managerId,
                        "MANAGER",
                        409);
        assertThat(expired.path("error").asText()).isEqualTo("PROPOSAL_EXPIRED");
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM governance_actions WHERE"
                                                + " governance_action_id=:id")
                                .param("id", proposal.path("approvalId").asLong())
                                .query(String.class)
                                .single())
                .isEqualTo("EXPIRED");
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(count("purchase_applications")).isZero();
    }

    @Test
    void expiredPolicyReviewTargetsTheUnfinishedOrchestratorParent() throws Exception {
        var proposal = propose();
        jdbc.sql("UPDATE supplier_material_terms SET unit_price=unit_price+1").update();
        decide(proposal.path("approvalId").asLong(), decisionBody(proposal,"APPROVE"), "parent-expired", managerId,"MANAGER",409);
        assertThat(jdbc.sql("SELECT w.work_item_ref FROM attention_requests a JOIN work_items w USING(work_item_id) WHERE a.title='재보충 방침 확인 필요'").query(String.class).single()).isEqualTo("WI-ORCH");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"DONE","CANCELLED","MISSING","WRONG_ROLE","HUMAN","INACTIVE","OTHER_CASE"})
    void policyReviewFallsBackWithoutAnEligibleSameCaseParent(String invalid) throws Exception {
        if (List.of("DONE","CANCELLED").contains(invalid))
            jdbc.sql("UPDATE work_items SET status=CAST(:status AS work_item_status),resolved_at=CURRENT_TIMESTAMP WHERE work_item_ref='WI-ORCH'").param("status",invalid).update();
        else if ("MISSING".equals(invalid))
            jdbc.sql("UPDATE work_items SET metadata='{}'::jsonb WHERE work_item_id=:id").param("id",workId).update();
        else if ("WRONG_ROLE".equals(invalid))
            jdbc.sql("UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE agent_key='SUPPLY_CHAIN') WHERE work_item_ref='WI-ORCH'").update();
        else if ("HUMAN".equals(invalid))
            jdbc.sql("UPDATE work_items SET assigned_agent_id=NULL,assigned_user_id=:id WHERE work_item_ref='WI-ORCH'").param("id",managerId).update();
        else if ("INACTIVE".equals(invalid))
            jdbc.sql("UPDATE agents SET is_active=false WHERE agent_key='ORCHESTRATOR'").update();
        else {
            long other=jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type,opened_by_user_id) VALUES('CASE-OTHER','Other','Other','ACT',:id) RETURNING case_id").param("id",managerId).query(Long.class).single();
            jdbc.sql("UPDATE work_items SET case_id=:id WHERE work_item_ref='WI-ORCH'").param("id",other).update();
        }
        var proposal=propose();
        decide(proposal.path("approvalId").asLong(),decisionBody(proposal,"BLOCK"),"invalid-parent",managerId,"MANAGER",200);
        assertThat(jdbc.sql("SELECT work_item_id IS NULL FROM attention_requests WHERE title='재보충 방침 확인 필요'").query(Boolean.class).single()).isTrue();
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(count("replenishment_followups")).isZero();
    }

    @Test
    void operatorCannotApproveEvenWhenSendingAnotherActorId() throws Exception {
        JsonNode proposal = propose();
        var body = mapper.readTree(decisionBody(proposal, "APPROVE")).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) body).put("actorId", managerId);
        decide(
                proposal.path("approvalId").asLong(),
                mapper.writeValueAsString(body),
                "forged",
                operatorId,
                "OPERATOR",
                403);
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
    }

    @Test
    void blockClosesPurchaseResponsibilityWithoutApplying() throws Exception {
        JsonNode proposal = propose();
        decide(
                proposal.path("approvalId").asLong(),
                decisionBody(proposal, "BLOCK"),
                "block",
                managerId,
                "MANAGER",
                200);
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(
                        jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                                .param("id", workId)
                                .query(String.class)
                                .single())
                .isEqualTo("CANCELLED");
        decide(
                proposal.path("approvalId").asLong(),
                decisionBody(proposal, "APPROVE"),
                "after-block",
                managerId,
                "MANAGER",
                409);
    }

    @Test
    void proposalAuditDoesNotImpersonateTheHumanRequester() throws Exception {
        JsonNode proposal = propose();
        long id = proposal.path("approvalId").asLong();
        assertThat(
                        jdbc.sql(
                                        "SELECT requested_by FROM governance_actions WHERE"
                                                + " governance_action_id=:id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(operatorId);
        assertThat(
                        jdbc.sql(
                                        "SELECT actor_id IS NULL FROM governance_audit_logs WHERE"
                                                + " governance_action_id=:id AND"
                                                + " event_type='PURCHASE_PROPOSED'")
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    void purchaseRevertedToDraftCannotBeReportedAsVerifiedCompletion() throws Exception {
        JsonNode proposal = propose();
        decide(
                proposal.path("approvalId").asLong(),
                decisionBody(proposal, "APPROVE"),
                "approve",
                managerId,
                "MANAGER",
                200);
        jdbc.sql(
                        "UPDATE purchase_orders SET status='DRAFT' WHERE purchase_application_id IS"
                                + " NOT NULL")
                .update();
        var next = execution.claim("cancel-check").orElseThrow();
        assertThatThrownBy(
                        () ->
                                execution.finish(
                                        next.runRef(),
                                        "cancel-check",
                                        next.leaseToken(),
                                        "DONE",
                                        "잘못된 완료",
                                        null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("COMPLETION_NOT_VERIFIED");
    }

    @Test
    void failedSecondLineRollsBackEverythingAndSameRequestCanRetry() throws Exception {
        JsonNode proposal = propose();
        long id = proposal.path("approvalId").asLong();
        String body = decisionBody(proposal, "APPROVE");
        jdbc.sql(
                        """
CREATE FUNCTION fail_second_purchase_line() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.purchase_quantity IS NOT NULL AND
    (SELECT count(*) FROM purchase_order_items WHERE purchase_quantity IS NOT NULL)>=2 THEN
    RAISE EXCEPTION 'test failure after two new purchase lines';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER fail_second_purchase_line AFTER INSERT ON purchase_order_items
  FOR EACH ROW EXECUTE FUNCTION fail_second_purchase_line();
""")
                .update();
        assertThatThrownBy(() -> decide(id, body, "failed-then-retry", managerId, "MANAGER", 200))
                .hasRootCauseMessage(
                        "ERROR: test failure after two new purchase lines\n"
                                + "  Where: PL/pgSQL function fail_second_purchase_line() line 5 at"
                                + " RAISE");
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(count("purchase_applications")).isZero();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM governance_decisions WHERE is_final")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM governance_actions WHERE"
                                                + " governance_action_id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM request_idempotency WHERE"
                                                + " request_key='failed-then-retry'")
                                .query(Long.class)
                                .single())
                .isZero();
        jdbc.sql(
                        "DROP TRIGGER fail_second_purchase_line ON purchase_order_items; DROP"
                                + " FUNCTION fail_second_purchase_line()")
                .update();
        decide(id, body, "failed-then-retry", managerId, "MANAGER", 200);
        assertThat(count("purchase_orders")).isEqualTo(originalOrders + 1);
    }

    @Test
    void approveAndBlockRaceProducesOnlyOneFinalDecision() throws Exception {
        JsonNode proposal = propose();
        long id = proposal.path("approvalId").asLong();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve =
                    pool.submit(
                            () -> {
                                start.await();
                                return decisionStatus(
                                        id, decisionBody(proposal, "APPROVE"), "race-approve");
                            });
            var block =
                    pool.submit(
                            () -> {
                                start.await();
                                return decisionStatus(
                                        id, decisionBody(proposal, "BLOCK"), "race-block");
                            });
            start.countDown();
            assertThat(List.of(approve.get(20, TimeUnit.SECONDS), block.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        jdbc.sql("SELECT count(*) FROM governance_decisions WHERE is_final")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        long applications = count("purchase_applications");
        assertThat(applications).isBetween(0L, 1L);
        assertThat(count("purchase_orders")).isEqualTo(originalOrders + applications);
    }

    @Test
    void wrongProposalVersionDoesNotInvalidateTheActualPendingRequest() throws Exception {
        JsonNode proposal = propose();
        long id = proposal.path("approvalId").asLong();
        var wrong =
                (tools.jackson.databind.node.ObjectNode)
                        mapper.readTree(decisionBody(proposal, "APPROVE"));
        wrong.put("expectedVersion", proposal.path("version").asInt() + 1);
        decide(id, mapper.writeValueAsString(wrong), "wrong-version", managerId, "MANAGER", 409);
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM governance_actions WHERE"
                                                + " governance_action_id=:id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo("PENDING");
        decide(id, decisionBody(proposal, "APPROVE"), "right-version", managerId, "MANAGER", 200);
    }

    @Test
    void aZeroPurchasePlanFinishesWithoutInventingAnApproval() throws Exception {
        jdbc.sql("UPDATE orders SET status='PENDING'").update();
        work("WI-SUPPLY-EMPTY", "SUPPLY_CHAIN");
        runs.createRun(
                new CreateRunRequest("SUPPLY_CHAIN", "CASE-PURCHASE", "WI-SUPPLY-EMPTY", "CODEX"),
                null);
        var supply = execution.claim("empty-supply-worker").orElseThrow();
        var products =
                jdbc.sql(
                                "SELECT product_id FROM products WHERE sku IN"
                                        + " ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                        .query(Long.class)
                        .list();
        plan =
                plans.calculate(
                        "CASE-PURCHASE",
                        new PlanRequest(warehouse, products, 30),
                        "empty-plan",
                        supply.capabilityToken());
        execution.finish(
                supply.runRef(),
                "empty-supply-worker",
                supply.leaseToken(),
                "DONE",
                "구매 불필요 계획 확인",
                null);
        JsonNode result = propose();
        assertThat(result.path("status").asText()).isEqualTo("NO_PURCHASE_REQUIRED");
        assertThat(count("replenishment_followups")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT due_at IS NULL AND purchase_application_id IS NULL FROM replenishment_followups")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT status::text FROM cases WHERE case_id=:id").param("id", caseId)
                .query(String.class).single()).isEqualTo("WAITING");
        assertThat(count("governance_actions")).isZero();
        assertThat(count("purchase_applications")).isZero();
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(
                        execution
                                .heartbeat(claim.runRef(), "purchase-worker", claim.leaseToken())
                                .outcome())
                .isEqualTo("DONE");
    }

    @Test
    void serverManagedWorkCannotBypassDispatchQueueClaimOrAttentionAnswer() throws Exception {
        completePurchase();
        long managed = jdbc.sql("SELECT work_item_id FROM replenishment_followups").query(Long.class).single();
        String ref = jdbc.sql("SELECT work_item_ref FROM work_items WHERE work_item_id=:id")
                .param("id", managed).query(String.class).single();
        jdbc.sql("INSERT INTO waiting_conditions(waiting_ref,work_item_id,condition_type,condition_payload,reason) VALUES('WAIT-BYPASS',:id,'SCHEDULED_TIME','{\"dueAt\":\"2020-01-01T00:00:00Z\"}','우회 금지')")
                .param("id", managed).update();
        long events = count("events");
        assertThat(dispatcher.dispatchScheduledIfActionable()).isEmpty();
        assertThat(count("events")).isEqualTo(events);
        assertThat(dispatcher.dispatchScheduled().scheduledRuns()).isEmpty();
        assertThat(jdbc.sql("SELECT status::text FROM waiting_conditions WHERE waiting_ref='WAIT-BYPASS'")
                .query(String.class).single()).isEqualTo("ACTIVE");
        jdbc.sql("UPDATE work_items SET status='READY' WHERE work_item_id=:id").param("id", managed).update();
        assertThatThrownBy(() -> runs.createRun(new CreateRunRequest("ORCHESTRATOR", "CASE-PURCHASE", ref, "CODEX"), null))
                .hasMessageContaining("SERVER_MANAGED_WORK_ITEM");
        jdbc.sql("UPDATE work_items SET status='WAITING' WHERE work_item_id=:id").param("id", managed).update();
        jdbc.sql("INSERT INTO runs(run_ref,agent_id,case_id,work_item_id,runtime,status) SELECT 'RUN-ROGUE',assigned_agent_id,case_id,work_item_id,'CODEX','QUEUED' FROM work_items WHERE work_item_id=:id")
                .param("id", managed).update();
        assertThat(execution.claim("rogue-worker")).isEmpty();
        assertThat(jdbc.sql("SELECT status::text FROM runs WHERE run_ref='RUN-ROGUE'").query(String.class).single()).isEqualTo("ABORTED");
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id").param("id", managed).query(String.class).single()).isEqualTo("WAITING");
        long attention = jdbc.sql("INSERT INTO attention_requests(case_id,work_item_id,reason_type,title,question,consequence) VALUES(:case,:work,'MISSING_HUMAN_CONTEXT','후속 확인','확인 내용?','책임 유지') RETURNING attention_request_id")
                .param("case", caseId).param("work", managed).query(Long.class).single();
        var actor = new HumanActor("https://fixture.example/", "operator", operatorId, "운영자", "OPERATOR", Set.of("work:write"));
        var receipt = answers.answer(attention, new AttentionAnswerRequest("현장 확인 중", 1, AttentionAnswerRequest.Scope.THIS_CASE), actor, "answer-managed");
        assertThat(receipt.path("resume").path("status").asText()).isEqualTo("SERVER_MANAGED");
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:id AND status IN ('QUEUED','RUNNING')").param("id", managed).query(Long.class).single()).isZero();
    }

    @Test
    void failedFollowupCreationRollsBackVerifiedDoneAndCanRetry() throws Exception {
        var next = approvePurchase();
        jdbc.sql("CREATE FUNCTION fail_followup() RETURNS TRIGGER AS $$ BEGIN RAISE EXCEPTION 'followup failure'; END; $$ LANGUAGE plpgsql; CREATE TRIGGER fail_followup BEFORE INSERT ON replenishment_followups FOR EACH ROW EXECUTE FUNCTION fail_followup()")
                .update();
        long events = count("events");
        assertThatThrownBy(() -> execution.finish(next.runRef(), "verify-fu", next.leaseToken(), "DONE", "확인", null))
                .hasMessageContaining("followup failure");
        assertThat(count("replenishment_followups")).isZero();
        assertThat(count("events")).isEqualTo(events);
        assertThat(jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:ref").param("ref", next.runRef()).query(String.class).single()).isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id").param("id", workId).query(String.class).single()).isEqualTo("IN_PROGRESS");
        jdbc.sql("DROP TRIGGER fail_followup ON replenishment_followups; DROP FUNCTION fail_followup()").update();
        execution.finish(next.runRef(), "verify-fu", next.leaseToken(), "DONE", "재시도 확인", null);
        assertThat(count("replenishment_followups")).isEqualTo(1);
    }

    @Test
    void changedFollowupPollReferencesItsOwnEventAndUnchangedPollIsQuiet() throws Exception {
        completePurchase();
        jdbc.sql("UPDATE purchase_order_items SET expected_delivery_date=DATE '2026-09-03' WHERE purchase_order_id IN (SELECT purchase_order_id FROM purchase_orders WHERE purchase_application_id IS NOT NULL)").update();
        jdbc.sql("UPDATE replenishment_followups SET due_at=TIMESTAMPTZ '2026-09-03T15:00:00Z'").update();
        long events = count("events");
        var changed = dispatcher.dispatchScheduledIfActionable().orElseThrow();
        assertThat(changed.scheduledRuns()).isEmpty();
        assertThat(jdbc.sql("SELECT work_item_id=(SELECT work_item_id FROM replenishment_followups) FROM events WHERE event_id=:id")
                .param("id", changed.eventId()).query(Boolean.class).single()).isTrue();
        assertThat(count("events")).isEqualTo(events + 1);
        assertThat(dispatcher.dispatchScheduledIfActionable()).isEmpty();
        assertThat(count("events")).isEqualTo(events + 1);
    }

    @Test
    void followupMetadataCannotGrantAnUnrelatedParentCompletion() throws Exception {
        completePurchase();
        work("WI-OTHER-ORCH", "ORCHESTRATOR");
        jdbc.sql("UPDATE work_items SET metadata=coalesce(metadata,'{}'::jsonb) || '{\"parentWorkItemRef\":\"WI-OTHER-ORCH\"}'::jsonb WHERE work_item_id IN (SELECT work_item_id FROM replenishment_followups)").update();
        runs.createRun(new CreateRunRequest("ORCHESTRATOR", "CASE-PURCHASE", "WI-OTHER-ORCH", "CODEX"), null);
        var other = execution.claim("other-parent").orElseThrow();
        assertThatThrownBy(() -> execution.finish(other.runRef(), "other-parent", other.leaseToken(), "DONE", "잘못된 책임", null))
                .hasMessageContaining("COMPLETION_NOT_VERIFIED");
    }

    @Test
    void monitorUsesTypedDeadlineEvenWhenLegacyDeadlineDisagrees() throws Exception {
        completePurchase();
        jdbc.sql("UPDATE replenishment_followups SET due_at=clock_timestamp()+interval '1 day'").update();
        jdbc.sql("UPDATE work_items SET due_at=TIMESTAMP '2020-01-01 00:00:00' WHERE work_item_id IN (SELECT work_item_id FROM replenishment_followups)").update();
        assertThat(interfaceReads.counts().casesAtRisk()).isZero();
        jdbc.sql("UPDATE work_items SET due_at=NULL WHERE work_item_id IN (SELECT work_item_id FROM replenishment_followups)").update();
        jdbc.sql("UPDATE replenishment_followups SET due_at=clock_timestamp()-interval '1 second'").update();
        assertThat(interfaceReads.counts().casesAtRisk()).isEqualTo(1);
    }

    private RunExecutionService.Claim approvePurchase() throws Exception {
        var proposal = propose();
        decide(proposal.path("approvalId").asLong(), decisionBody(proposal, "APPROVE"), "approve-fu", managerId, "MANAGER", 200);
        return execution.claim("verify-fu").orElseThrow();
    }

    private void completePurchase() throws Exception {
        var next = approvePurchase();
        execution.finish(next.runRef(), "verify-fu", next.leaseToken(), "DONE", "발주 확인", null);
    }

    private int decisionStatus(long id, String body, String key) throws Exception {
        var actor =
                new HumanActor(
                        "https://fixture.example/",
                        "manager",
                        managerId,
                        "관리자",
                        "MANAGER",
                        Set.of("procurement:decide"));
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        actor, null, List.of(new SimpleGrantedAuthority("procurement:decide")));
        return mvc.perform(
                        post("/api/v1/approvals/{id}/decision", id)
                                .with(authentication(auth))
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
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
