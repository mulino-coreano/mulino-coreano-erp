package com.mulinocoreano.backend.scenario;

import io.cucumber.java.After;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

public class AgentSteps {
    @Autowired ScenarioWorld world;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired BusinessState state;
    AgentDriver driver;

    Duration timeout() { return ScenarioContext.live() ? Duration.ofMinutes(15) : Duration.ofSeconds(60); }

    AgentDriver driver() {
        if (driver == null) {
            driver = new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/scripts/scripted-runner.mjs").toString()));
            driver.start(scriptedEnv());
        }
        return driver;
    }

    Map<String, String> scriptedEnv() {
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        List<Long> products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        return Map.of("MULINO_API_BASE", world.apiBase(), "MULINO_WORKER_TOKEN", ScenarioContext.WORKER_TOKEN,
                "DEMO_CLI", HumanChannel.ROOT.resolve("agents/cli/zig-out/bin/mulino").toString(),
                "DEMO_PLAN_INPUT", "{\"warehouseId\":" + warehouse + ",\"productIds\":" + products + "}");
    }

    @When("에이전트가 소요량 계획과 구매 제안을 작성한다")
    public void agentPlansAndProposes() {
        driver().awaitState("구매 제안 승인 대기", () -> state.pendingApprovals(world.caseRef) >= 1, timeout());
    }

    @When("에이전트가 발주 이후 업무를 정리한다")
    public void agentHandlesAfterPurchase() {
        driver().awaitState("입고 확인 후속 업무", () -> state.followups(world.caseRef) == 1, timeout());
    }

    @When("에이전트가 반려를 확인한다")
    public void agentSeesBlock() {
        driver().awaitState("반려 후 에이전트 중단", () -> state.abortedOrchestratorRuns(world.caseRef) >= 1, timeout());
    }

    @After
    public void stopAgent() { if (driver != null) driver.stop(); }
}
