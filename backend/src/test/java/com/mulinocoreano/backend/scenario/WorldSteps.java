package com.mulinocoreano.backend.scenario;

import io.cucumber.java.en.When;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** 외부 세계의 변화(공급사 단가)와 여러 역할이 얽힌 단계. */
public class WorldSteps {
    @Autowired ScenarioWorld world;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired BusinessState state;
    @Autowired AgentSteps agent;

    @When("공급사가 DEMO 밀가루 단가를 {int}원에서 {int}원으로 올린다")
    public void supplierRaisesFlourPrice(int from, int to) {
        int updated = jdbc.sql("""
                UPDATE supplier_material_terms t SET unit_price=:to
                FROM raw_materials m WHERE m.raw_material_id=t.raw_material_id AND m.name='DEMO 밀가루' AND t.unit_price=:from
                """).param("to", to).param("from", from).update();
        if (updated != 1) throw new AssertionError("expected exactly one flour term at " + from);
    }

    @When("에이전트가 만료를 확인하고 사람에게 묻는다")
    public void agentAsksAfterExpiry() {
        agent.driver().awaitState("만료 후 사람 질의", () -> state.openAttentionWithoutApproval(world.caseRef) == 1, agent.timeout());
    }

    @When("MANAGER가 변경된 단가로 재계산을 지시한다")
    public void managerOrdersRecalculation() {
        var human = new HumanSteps().withContext(world, mapper);
        var view = new HumanChannel(mapper, world.apiBase()).call("MANAGER", "get_case", Map.of("caseRef", world.caseRef)).content();
        // CaseOverviewRepository.attention() always emits the governanceActionId key (jOOQ's row-to-map
        // conversion puts every column, null or not), and the default Jackson ObjectMapper serializes a
        // null map value as a JSON null rather than omitting the key. So an unlinked Attention arrives as
        // governanceActionId: null, a NullNode -- isMissingNode() would never match it. Check isNull() too.
        var attention = java.util.stream.StreamSupport.stream(view.path("attention").spliterator(), false)
                .filter(a -> "OPEN".equals(a.path("status").asText())
                        && (a.path("governanceActionId").isMissingNode() || a.path("governanceActionId").isNull()))
                .findFirst().orElseThrow();
        String oldPlan = human.latestApproval("MANAGER").path("planRef").asText();
        var r = new HumanChannel(mapper, world.apiBase()).call("MANAGER", "answer_attention", Map.of(
                "attentionRequestId", attention.path("attentionRequestId").asLong(),
                "expectedVersion", attention.path("version").asInt(),
                "scope", "THIS_CASE",
                "answer", "Recalculate this Case using the changed supplier price; request a fresh purchase approval. Source plan: " + oldPlan + ".",
                "requestKey", "scenario-recalculate"));
        if (r.isError()) throw new AssertionError("recalculation answer rejected");
    }

    /** Distinct MULINO_WORKER_ID for the runner started after the hard kill below. Referenced by
     * StateSteps too, to confirm the restart was not vacuous (some Run really was claimed by it). */
    static final String RESTARTED_WORKER_ID = "scenario-scripted-restarted";

    @When("에이전트 실행기가 소요량 계획 직후 재시작된다")
    public void runnerRestartsAfterPlan() {
        // Model an actual crash, deterministically, instead of timing a graceful shutdown around a
        // live, independently-polling process (that approach -- wait for quiescence, then stop(),
        // then repair -- still raced against Runner.loop()'s own 200ms poll cycle and RunExecutionService
        // .claimLocked() committing a claim before it ever tries to write the HTTP response back, so a
        // killed client could not undo an already-committed claim; see task-4-report.md round 1).
        // Killing with no SIGTERM at all (AgentDriver.kill()) sidesteps that entirely: the runner never
        // gets to notice anything and self-report ABORTED, so whatever Run it happened to hold simply
        // stays RUNNING under its own lease. That lease is 60s (RunExecutionRepository.LEASE; 600s is
        // only the total per-Run runtime cap, not the lease), after which recoverExpired() -- run at
        // the top of every claim() -- reclaims and requeues it automatically, exactly like a real crash
        // recovers today. Because correctness no longer depends on catching a precise instant, this
        // only waits for a plan to exist and no purchase proposal yet (mid-flow, not full quiescence):
        // restarting after the proposal already exists would prove nothing (a vacuous restart).
        agent.driver().awaitState("소요량 계획 저장, 구매 제안 이전",
                () -> state.plans(world.caseRef) == 1 && state.pendingApprovals(world.caseRef) == 0,
                agent.timeout());
        agent.driver().kill();
        // A fresh runner reusing the SAME workerId would find the dead worker's lease still live
        // (its 60s hasn't passed yet) and get WORKER_ALREADY_LEASED (409) on every claim() until it
        // does -- stacking a 60s cooldown (Runner.executeOne()'s handling of 409) on top of the 60s
        // lease itself. A distinct workerId avoids that conflict entirely; the old lease still expires
        // and gets reclaimed on its own, for whichever worker polls next.
        agent.afterRestart = true;
        agent.driver().start(agent.scriptedEnv(RESTARTED_WORKER_ID));
    }
}
