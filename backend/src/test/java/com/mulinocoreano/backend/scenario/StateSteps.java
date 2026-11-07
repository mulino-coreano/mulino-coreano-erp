package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** 시나리오 '그러면' 스텝. ERP 업무 상태만 확인하고, 응답 문구·필드 존재는 보지 않는다. */
public class StateSteps {
    @Autowired ScenarioWorld world;
    @Autowired BusinessState state;
    @Autowired ObjectMapper mapper;

    @Then("구매 제안은 승인 대기 상태다")
    public void pending() {
        assertThat(state.latestApprovalStatus(world.caseRef())).isEqualTo("PENDING");
    }

    @Then("구매 제안은 반려 상태다")
    public void blocked() {
        assertThat(state.latestApprovalStatus(world.caseRef())).isEqualTo("BLOCKED");
    }

    @Then("구매 제안은 만료 상태다")
    public void expired() {
        assertThat(state.latestApprovalStatus(world.caseRef())).isEqualTo("EXPIRED");
    }

    @Then("구매 제안 합계는 {long}원이다")
    public void proposalTotal(long krw) {
        var approval = new HumanSteps().withContext(world, mapper).latestApproval("MANAGER");
        assertThat(new BigDecimal(approval.path("proposal").path("totalKrw").asText()))
                .isEqualByComparingTo(BigDecimal.valueOf(krw));
    }

    @Then("발주는 생성되지 않았다")
    public void noPurchaseOrder() {
        assertThat(state.appliedPurchaseOrders()).isZero();
    }

    @Then("발주 금액 합계는 {long}원이다")
    public void purchaseTotal(long krw) {
        assertThat(state.appliedPurchaseOrders()).isEqualTo(1);
        assertThat(state.appliedPurchaseTotal()).isEqualByComparingTo(BigDecimal.valueOf(krw));
    }

    @Then("Case는 입고 확인을 기다린다")
    public void caseWaitsForReceipt() {
        assertThat(state.caseStatus(world.caseRef())).isEqualTo("WAITING");
        assertThat(state.followups(world.caseRef())).isEqualTo(1);
    }

    @Then("후속 업무는 생성되지 않았다")
    public void noFollowup() {
        assertThat(state.followups(world.caseRef())).isZero();
    }

    @Then("소요량 계획은 한 번만 계산되었다")
    public void planCalculatedOnce() {
        assertThat(state.plans(world.caseRef())).isEqualTo(1);
        assertThat(state.completedSupplyRuns(world.caseRef())).isEqualTo(1);
        // The restart itself must not be vacuous: at least one Run for this Case has to have been
        // actually claimed by the restarted worker, or "restart" would have proven nothing (the
        // original worker could have quietly finished everything before the kill even landed).
        assertThat(state.runsClaimedBy(world.caseRef(), WorldSteps.RESTARTED_WORKER_ID))
                .as("the restarted worker must have claimed at least one Run").isGreaterThan(0);
    }
}
