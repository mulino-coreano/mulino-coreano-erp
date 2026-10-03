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
        world.caseRef(r.content().path("caseRef").asText());
    }

    @When("MANAGER가 구매 제안을 승인한다")
    public void managerApproves() { decide("MANAGER", "APPROVE", "scenario-approve"); }

    @When("MANAGER가 구매 제안을 반려한다")
    public void managerBlocks() { decide("MANAGER", "BLOCK", "scenario-block"); }

    @When("OPERATOR가 구매 제안 승인을 시도한다")
    public void operatorTriesToApprove() { decide("OPERATOR", "APPROVE", "scenario-operator"); }

    @When("MANAGER가 기존 구매 제안 승인을 시도한다")
    public void managerTriesStaleApproval() { decide("MANAGER", "APPROVE", "scenario-stale"); }

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

    /** 다른 스텝 클래스가 MCP 왕복 코드를 중복하지 않고 latestApproval을 재사용하기 위한 헬퍼. */
    HumanSteps withContext(ScenarioWorld world, ObjectMapper mapper) {
        this.world = world;
        this.mapper = mapper;
        return this;
    }

    JsonNode latestApproval(String role) {
        JsonNode attention = channel().call(role, "list_attention", Map.of()).content().path("attention");
        long id = world.approvalId() == null ? -1 : world.approvalId();
        for (JsonNode item : attention) {
            if (world.caseRef().equals(item.path("caseRef").asText()) && item.hasNonNull("governanceActionId"))
                id = Math.max(id,item.path("governanceActionId").asLong());
        }
        if (id < 0) throw new AssertionError("Case " + world.caseRef() + " has no purchase approvals");
        world.approvalId(id);
        return channel().call(role, "get_approval", Map.of("approvalId", id)).content();
    }
}
