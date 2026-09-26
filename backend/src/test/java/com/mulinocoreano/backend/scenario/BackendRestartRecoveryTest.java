package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 목표 4(업무 연속): Cucumber 시나리오는 사람이 읽는 업무 언어로만 단계를 쓰므로 "실제 백엔드
 * 애플리케이션 컨텍스트가 통째로 재시작된다"는 인프라 사건을 Gherkin으로 표현할 수 없다. 승인
 * 대기 중 백엔드가 재시작되어도 같은 제안을 승인하면 발주가 정확히 한 번 생기고, 소요량 계획은
 * 다시 계산되지 않는다는 것을 JUnit으로 증명한다.
 */
@Tag("scenario")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.schemas=scenario", "spring.flyway.clean-disabled=false",
        "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
        "spring.datasource.hikari.schema=scenario", "spring.main.allow-bean-definition-overriding=true",
        "mulino.local-auth.worker-token=" + ScenarioContext.WORKER_TOKEN})
class BackendRestartRecoveryTest {

    /**
     * Spring Boot auto-detects a nested @TestConfiguration class and applies it after the main
     * configuration, so its bean definitions win even with allow-bean-definition-overriding. An
     * {@code @Import(ScenarioContext.FixedClock.class)} on the test class itself was tried first
     * and does NOT reliably win that ordering outside Cucumber's bootstrap: PlanningConfiguration's
     * real-clock "planningClock" bean ended up replacing the fixed one, so the plan calculation ran
     * against the real 2026-09-27 wall clock instead of the fixture's 2026-09-05 and produced
     * NO_PURCHASE_REQUIRED. Declaring the fixed clock in this test's own nested @TestConfiguration
     * avoids that ordering dependency, matching the retired backend acceptance test that this class
     * replaces.
     */
    @TestConfiguration
    static class Config {
        @Bean("backendRestartContextIdentity")
        String contextIdentity() { return UUID.randomUUID().toString(); }

        @Bean("planningClock") @Primary
        Clock planningClock() { return Clock.fixed(ScenarioContext.BUSINESS_NOW, ZoneId.of("Asia/Seoul")); }
    }

    static String caseRef;
    static String previousContextIdentity;

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired ObjectMapper mapper;
    @Autowired BusinessState state;
    @Autowired @Qualifier("backendRestartContextIdentity") String contextIdentity;

    @BeforeAll
    static void guardDatabase() {
        ScenarioGuard.requireDisposable(System.getenv("DB_URL"));
    }

    String apiBase() { return "http://127.0.0.1:" + port + "/api/v1"; }
    HumanChannel channel() { return new HumanChannel(mapper, apiBase()); }

    @Test @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void preparePendingApprovalThenStopTheRealApplicationContext() throws Exception {
        flyway.clean();
        flyway.migrate();
        ReplenishmentDemoFixture.load(jdbc);

        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        List<Long> products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();

        var intake = channel().call("OPERATOR", "create_case", Map.of(
                "objective", "DEMO-AMR·DEMO-BSC 재보충",
                "requestKey", "restart-case",
                "replenishment", Map.of("productSkus", List.of("DEMO-AMR", "DEMO-BSC"),
                        "warehouseId", warehouse, "targetDate", "2026-10-04")));
        assertThat(intake.isError()).as("case intake").isFalse();
        caseRef = intake.content().path("caseRef").asText();

        var driver = new AgentDriver(mapper, List.of("node",
                HumanChannel.ROOT.resolve("agents/runner/scripts/scripted-runner.mjs").toString()));
        try {
            driver.start(Map.of(
                    "MULINO_API_BASE", apiBase(),
                    "MULINO_WORKER_TOKEN", ScenarioContext.WORKER_TOKEN,
                    "DEMO_CLI", HumanChannel.ROOT.resolve("agents/cli/zig-out/bin/mulino").toString(),
                    "DEMO_PLAN_INPUT", "{\"warehouseId\":" + warehouse + ",\"productIds\":" + products + "}"));
            driver.awaitState("구매 제안 승인 대기", () -> state.pendingApprovals(caseRef) >= 1, Duration.ofSeconds(60));
        } finally {
            // The application context is about to be torn down (DirtiesContext AFTER_METHOD); the
            // runner and its child scripted agent must not be left orphaned across that boundary.
            driver.stop();
        }

        assertThat(state.pendingApprovals(caseRef)).as("purchase approval pending before restart").isEqualTo(1);
        assertThat(state.plans(caseRef)).as("plan calculated exactly once before restart").isEqualTo(1);
        previousContextIdentity = contextIdentity;
    }

    @Test @Order(2)
    void restartTheRealApplicationAndResumePersistedApproval() {
        assertThat(previousContextIdentity).as("Preparation must complete before restart assertion").isNotNull();
        // RANDOM_PORT may legitimately reuse the old port; the fresh bean instance proves the
        // application context (and therefore its in-memory state) was actually recreated.
        assertThat(contextIdentity).as("A new application context must have been created").isNotEqualTo(previousContextIdentity);

        assertThat(state.pendingApprovals(caseRef)).as("the pending approval survives the restart").isEqualTo(1);

        JsonNode caseView = channel().call("MANAGER", "get_case", Map.of("caseRef", caseRef)).content();
        long approvalId = -1;
        for (JsonNode approval : caseView.path("approvals")) {
            long candidate = approval.path("governanceActionId").asLong();
            if (candidate > approvalId) approvalId = candidate;
        }
        assertThat(approvalId).as("Case must still carry the pending purchase approval").isGreaterThanOrEqualTo(0);
        JsonNode approval = channel().call("MANAGER", "get_approval", Map.of("approvalId", approvalId)).content();

        var decision = channel().call("MANAGER", "decide_purchase", Map.of(
                "approvalId", approvalId,
                "decision", "APPROVE",
                "expectedVersion", approval.path("version").asInt(),
                "proposalHash", approval.path("proposalHash").asText(),
                "reason", "restart recovery approve",
                "requestKey", "restart-approve"));
        assertThat(decision.isError()).as("approval after restart").isFalse();

        assertThat(state.appliedPurchaseOrders()).as("exactly one purchase order applied").isEqualTo(1);
        assertThat(state.appliedPurchaseTotal()).as("applied purchase total").isEqualByComparingTo(BigDecimal.valueOf(16500));
        assertThat(state.plans(caseRef)).as("the plan was not recalculated after restart").isEqualTo(1);
    }
}
