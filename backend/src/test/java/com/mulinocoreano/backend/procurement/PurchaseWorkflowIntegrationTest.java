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
    @Autowired RunExecutionService execution;
    @Autowired PlanPersistenceService plans;
    @MockitoSpyBean ReplenishmentCalculator calculator;
    @MockitoSpyBean PurchasePlanRepository purchasePlans;
    long managerId, operatorId, caseId, workId, warehouse;
    PlanDto plan;
    RunExecutionService.Claim claim;
    long originalOrders;

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
                .isNotIn("RESOLVED", "CLOSED");
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
        assertThat(count("governance_actions")).isZero();
        assertThat(count("purchase_applications")).isZero();
        assertThat(count("purchase_orders")).isEqualTo(originalOrders);
        assertThat(
                        execution
                                .heartbeat(claim.runRef(), "purchase-worker", claim.leaseToken())
                                .outcome())
                .isEqualTo("DONE");
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
