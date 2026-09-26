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
        agent.driver().awaitState("소요량 계획 저장", () -> state.plans(world.caseRef) == 1, agent.timeout());
        agent.driver().restart();
    }
}
