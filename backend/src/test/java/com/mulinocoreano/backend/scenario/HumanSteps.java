package com.mulinocoreano.backend.scenario;

import io.cucumber.java.en.When;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class HumanSteps {
    @Autowired ScenarioWorld world;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    private HumanChannel channel() { return new HumanChannel(mapper, world.apiBase()); }

    @When("OPERATOR가 DEMO-AMR·DEMO-BSC 재보충 목표를 접수한다")
    public void operatorOpensReplenishment() {
        long warehouse = jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'").query(Long.class).single();
        var r = channel().call("OPERATOR", "create_case", Map.of(
                "objective", "DEMO-AMR·DEMO-BSC 재보충",
                "requestKey", "scenario-case",
                "replenishment", Map.of("productSkus", List.of("DEMO-AMR", "DEMO-BSC"),
                        "warehouseId", warehouse, "targetDate", "2026-10-04")));
        if (r.isError()) throw new AssertionError("Case intake rejected");
        world.caseRef = r.content().path("caseRef").asText();
    }

    @When("MANAGER가 구매 제안을 승인한다")
    public void managerApproves() { world.lastDecision = decide("MANAGER", "APPROVE", "scenario-approve"); }

    @When("MANAGER가 구매 제안을 반려한다")
    public void managerBlocks() { world.lastDecision = decide("MANAGER", "BLOCK", "scenario-block"); }

    @When("OPERATOR가 구매 제안 승인을 시도한다")
    public void operatorTriesToApprove() { world.lastDecision = decide("OPERATOR", "APPROVE", "scenario-operator"); }

    @When("MANAGER가 기존 구매 제안 승인을 시도한다")
    public void managerTriesStaleApproval() { world.lastDecision = decide("MANAGER", "APPROVE", "scenario-stale"); }

    /** 가장 최근 구매 제안을 현재 버전·해시로 결정한다. 거절 여부는 업무 상태로 확인한다. */
    HumanChannel.ToolResult decide(String role, String decision, String requestKey) {
        JsonNode approval = latestApproval("MANAGER");
        return channel().call(role, "decide_purchase", Map.of(
                "approvalId", approval.path("id").asLong(),
                "decision", decision,
                "expectedVersion", approval.path("version").asInt(),
                "proposalHash", approval.path("proposalHash").asText(),
                "reason", "scenario " + decision,
                "requestKey", requestKey));
    }

    JsonNode latestApproval(String role) {
        JsonNode view = channel().call(role, "get_case", Map.of("caseRef", world.caseRef)).content();
        JsonNode approvals = view.path("approvals");
        // CaseOverviewRepository.approvals() orders by governance_action_id DESC, but that
        // ordering is an implementation detail this step must not depend on. Pick the highest
        // governanceActionId explicitly so "latest" holds regardless of array order.
        long id = -1;
        for (JsonNode approval : approvals) {
            long candidate = approval.path("governanceActionId").asLong();
            if (candidate > id) id = candidate;
        }
        if (id < 0) throw new AssertionError("Case " + world.caseRef + " has no purchase approvals");
        return channel().call(role, "get_approval", Map.of("approvalId", id)).content();
    }
}
