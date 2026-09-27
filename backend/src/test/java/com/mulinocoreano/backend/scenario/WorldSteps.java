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

    @When("에이전트 실행기가 소요량 계획 직후 재시작된다")
    public void runnerRestartsAfterPlan() {
        // Window 1: plans==1만 보고 바로 재시작하면 SUPPLY_CHAIN의 Run이 아직 RUNNING(plan calculate
        // 이후 plan show를 부르고 DONE으로 끝내는 구간, scripted-agent.mjs)일 때 실행기를 죽일 수 있다.
        // 그러면 Runner.executeOne()이 shutdown 신호를 보고 그 Run을 outcome=ABORTED로 스스로
        // 보고하고, RunExecutionService는 이런 명시적 ABORTED를 "사람 재검토 필요"로 막는다(자동
        // 재청구는 만료된 리스에만 적용, RunExecutionService.finishLocked 참고) — 다음 대기가 60초
        // 안에 끝나지 않는다. completedSupplyRuns==1까지 기다려 이 창을 피한다.
        agent.driver().awaitState("소요량 계획 완료",
                () -> state.completedSupplyRuns(world.caseRef) == 1 && state.runningRuns(world.caseRef) == 0,
                agent.timeout());
        agent.driver().stop();
        releaseAnyOrphanedRun();
        agent.driver().start(agent.scriptedEnv());
    }

    /**
     * Window 2, found by running this suite repeatedly: {@code stop()} above only guarantees the
     * old runner's OS process is dead, not that no claim of its was already headed to the server.
     * SUPPLY_CHAIN finishing DONE typically resumes the ORCHESTRATOR Run synchronously, in the same
     * request (RunExecutionService.recordFinish, called from finishLocked, ingests a
     * WORK_ITEM_STATUS_CHANGED event for a DONE outcome), so the runner's very next poll -- already
     * in flight or about to fire independently of our 250ms check above (Runner.loop polls every
     * 200ms on its own clock, not ours) -- can claim it. RunExecutionService.claimLocked() commits that
     * claim (status=RUNNING, a fresh lease) before it ever tries to write the HTTP response back,
     * so killing the client after the fact cannot undo it: the observed failure was exactly this,
     * a Run claimed by the dying worker sitting RUNNING for the full 600s
     * (RunExecutionRepository.MAX_RUNTIME) lease before recoverExpired() finally reclaimed it --
     * far past this scenario's 60s wait.
     * <p>
     * Runner.runOnce() only ever has one such call in flight at a time (guarded by
     * {@code this.pending}), and once its process is confirmed dead no more can follow, so at most
     * one straggler can still land, and only within ordinary local-loopback latency. Poll briefly
     * for it and, once seen, release it through the exact path the system already uses for a lease
     * that outlived its worker (recoverExpired(), run at the top of every claim()): back-date
     * lease_expires_at into the past so the new runner's own first claim() reclaims and requeues it
     * before claiming anything else. This reuses the production recovery path rather than
     * hand-rolling the retry bookkeeping (ABORTED record, work item back to READY, fresh Run) here.
     */
    private void releaseAnyOrphanedRun() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            int released = jdbc.sql("""
                    UPDATE runs r SET lease_expires_at = now() - interval '1 second'
                    FROM cases c WHERE c.case_id=r.case_id AND c.case_ref=:caseRef AND r.status='RUNNING'
                    """).param("caseRef", world.caseRef).update();
            if (released > 0) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}
