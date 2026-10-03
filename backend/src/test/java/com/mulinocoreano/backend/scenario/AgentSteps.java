package com.mulinocoreano.backend.scenario;

import io.cucumber.java.After;
import io.cucumber.java.en.When;
import java.nio.file.Path;
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
    /** Set by WorldSteps right after a hard kill+restart, so the very next wait below can afford
     * the killed Run's lease to actually expire (60s) and recoverExpired() to requeue it, on top of
     * the new worker then re-running the interrupted step. Scenario-scoped like this bean, so it
     * never leaks into another scenario. */
    private boolean afterRestart = false;
    void markAfterRestart() { afterRestart=true; }
    List<String> missingPrerequisites = List.of();

    Duration timeout() { return ScenarioContext.live() ? Duration.ofMinutes(15) : Duration.ofSeconds(60); }

    private static Duration atLeast(Duration base, Duration minimum) {
        return base.compareTo(minimum) >= 0 ? base : minimum;
    }

    @io.cucumber.java.Before(order = 1)
    public void skipUatWithoutPrerequisites() throws Exception {
        if (!ScenarioContext.live()) return;
        var missing = UatEvidence.prerequisitesMissing(System.getenv());
        if (missing.isEmpty()) missing = UatEvidence.infrastructureMissing(System.getenv());
        missingPrerequisites = missing;
        org.junit.jupiter.api.Assumptions.assumeTrue(missing.isEmpty(), "UAT skipped: prerequisite missing " + missing);
        UatEvidence.verifyContainerRoute(System.getenv(),"http://host.docker.internal:"+world.port()+"/api/v1");
        org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.getBoolean("mulino.scenario.route-only"),"UAT route smoke passed; modelRequests=0");
    }

    AgentDriver driver() {
        if (driver == null) {
            var env = System.getenv();
            driver = ScenarioContext.live()
                    ? new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/src/main.js").toString()))
                    : new AgentDriver(mapper, List.of("node", HumanChannel.ROOT.resolve("agents/runner/scripts/scripted-runner.mjs").toString()));
            driver.start(ScenarioContext.live() ? liveEnv(env) : scriptedEnv());
        }
        return driver;
    }

    Map<String, String> liveEnv(Map<String, String> env) {
        var m = new java.util.HashMap<String, String>();
        m.put("MULINO_LOCAL_SERVICE_SECRET", ScenarioContext.SERVICE_SECRET);
        m.put("MULINO_WORKER_ID", "uat-" + world.port());
        m.put("MULINO_API_BASE", world.apiBase());
        m.put("MULINO_AGENT_API_URL", "http://host.docker.internal:" + world.port() + "/api/v1");
        for (String k : List.of("MULINO_AGENT_RUNTIME", "MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME", "DOCKER_HOST"))
            if (env.get(k) != null) m.put(k, env.get(k));
        return m;
    }

    Map<String, String> scriptedEnv() { return scriptedEnv(null); }

    /** @param workerId overrides MULINO_WORKER_ID (defaults to "scenario-scripted" in
     * scripted-runner.mjs) when non-null -- used to give a runner started after a kill a distinct
     * identity, so it never collides with the dead worker's still-live lease (WORKER_ALREADY_LEASED). */
    Map<String, String> scriptedEnv(String workerId) {
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        List<Long> products = jdbc.sql("SELECT product_id FROM products WHERE sku IN ('DEMO-AMR','DEMO-BSC') ORDER BY product_id").query(Long.class).list();
        var env = new java.util.HashMap<>(Map.of("MULINO_API_BASE", world.apiBase(), "MULINO_LOCAL_SERVICE_SECRET", ScenarioContext.SERVICE_SECRET,
                "DEMO_CLI", HumanChannel.ROOT.resolve("agents/cli/zig-out/bin/mulino").toString(),
                "DEMO_PLAN_INPUT", "{\"warehouseId\":" + warehouse + ",\"productIds\":" + products + "}"));
        if (workerId != null) env.put("MULINO_WORKER_ID", workerId);
        return env;
    }

    @When("에이전트가 소요량 계획과 구매 제안을 작성한다")
    public void agentPlansAndProposes() {
        // A restart just before this call (WorldSteps.runnerRestartsAfterPlan) may leave the killed
        // worker's Run RUNNING under a lease that only expires naturally 60s later
        // (RunExecutionRepository.LEASE), before the server's recoverExpired() requeues it and the
        // new worker re-runs the interrupted step -- the plain 60s timeout() below is not enough
        // margin for that plus the actual work. Everywhere else this scenario doesn't restart, so
        // afterRestart stays false and the wait is unchanged.
        Duration wait = afterRestart ? atLeast(timeout(), Duration.ofSeconds(150)) : timeout();
        driver().awaitState("구매 제안 승인 대기", () -> state.pendingApprovals(world.caseRef()) >= 1, () -> state.latestRunFailed(world.caseRef()), wait);
        if (ScenarioContext.live()) driver().awaitState("구매 모델 종료와 사용량 기록",
                () -> state.runsForCase(world.caseRef()).stream().filter(r -> "PROCUREMENT".equals(r.agentKey()) && "WAITING".equals(r.outcome()))
                    .allMatch(r -> driver.modelFinished().stream().anyMatch(e -> r.runRef().equals(e.path("runRef").asText()) && UatEvidence.completeUsage(e))), wait);
        if (ScenarioContext.live()) {
            var pending = new java.util.LinkedHashMap<String,Object>();
            pending.put("approvalStatus",state.latestApprovalStatus(world.caseRef()));
            pending.put("appliedPurchaseOrders",state.appliedPurchaseOrders());
            pending.putAll(UatEvidence.summarize(state.runsForCase(world.caseRef()),driver.modelFinished()));
            world.pendingEvidence().add(pending);
        }
    }

    @When("조정 에이전트가 저장된 후속 책임을 확인한다")
    public void coordinatorConfirmsFollowup() {
        driver().awaitState("원본 조정 업무 완료",() -> state.originalCoordinatorDone(world.caseRef()),timeout());
        if (ScenarioContext.live()) driver().awaitState("모든 실행의 최종 사용량",
            () -> state.runsForCase(world.caseRef()).stream().allMatch(r -> driver.modelFinished().stream()
                .anyMatch(e -> r.runRef().equals(e.path("runRef").asText()) && UatEvidence.completeUsage(e))),timeout());
    }

    @When("에이전트가 반려를 확인한다")
    public void agentSeesBlock() {
        driver().awaitState("반려 후 에이전트 중단", () -> state.abortedOrchestratorRuns(world.caseRef()) >= 1, timeout());
    }

    @After(order = 10)
    public void recordUatEvidence(io.cucumber.java.Scenario scenario) throws Exception {
        if (!ScenarioContext.live()) return;
        String tc = scenario.getSourceTagNames().stream().filter(t -> t.startsWith("@TC-")).findFirst().orElse("@TC-UNKNOWN").substring(1);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("testCase", tc);
        record.put("status", scenario.getStatus().name());
        record.put("runtime", System.getenv("MULINO_AGENT_RUNTIME"));
        record.put("image", System.getenv("MULINO_RUNTIME_IMAGE"));
        record.put("nativeUsageEvents", driver == null ? List.of() : driver.modelFinished());
        record.put("beforeHumanDecision", world.pendingEvidence());
        record.put("missingPrerequisites", missingPrerequisites);
        record.put("model", System.getenv("MULINO_AGENT_MODEL"));
        record.putAll(UatEvidence.summarize(world.caseRef() == null ? List.of() : state.runsForCase(world.caseRef()), driver == null ? List.of() : driver.modelFinished()));
        record.put("appliedPurchaseOrders", state.appliedPurchaseOrders());
        record.put("appliedPurchaseTotalKrw", state.appliedPurchaseTotal());
        record.put("caseStatus", world.caseRef() == null ? null : state.caseStatus(world.caseRef()));
        Path file = UatEvidence.write(mapper, Path.of("build/uat", java.time.LocalDate.now().toString()), tc, record);
        scenario.log("UAT evidence: " + file);
    }

    @After
    public void stopAgent() { if (driver != null) driver.stop(); }
}
