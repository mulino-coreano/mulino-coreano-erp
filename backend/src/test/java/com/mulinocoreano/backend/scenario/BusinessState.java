package com.mulinocoreano.backend.scenario;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 업무 상태 조회 하나로 시나리오 스텝이 SQL을 직접 쓰지 않게 한다. Task 3는 에이전트 드라이버가
 * 기다리는 상태만 담는다. Task 4가 나머지 목표(입고 확인, 반려 후 상태 등)에 필요한 메서드를 더한다.
 */
@Component
public class BusinessState {
    private final JdbcClient jdbc;

    public BusinessState(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 이 Case의 구매 제안 중 PENDING인 승인 건수. */
    public long pendingApprovals(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM governance_actions ga JOIN cases c ON c.case_id=ga.case_id
                WHERE c.case_ref=:caseRef AND ga.status='PENDING'
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 이 Case에 대해 생성된 입고 확인 후속 업무(replenishment_followups) 건수. */
    public long followups(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM replenishment_followups f JOIN cases c ON c.case_id=f.case_id
                WHERE c.case_ref=:caseRef
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 반려 이후 사람 정책 재검토를 기다리며 중단된 이 Case의 ORCHESTRATOR Run 건수. */
    public long abortedOrchestratorRuns(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM runs r JOIN agents a USING(agent_id) JOIN cases c ON c.case_id=r.case_id
                WHERE c.case_ref=:caseRef AND a.agent_key='ORCHESTRATOR' AND r.outcome='ABORTED'
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 이 Case에 저장된 소요량 계획(replenishment_plans) 건수. */
    public long plans(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM replenishment_plans p JOIN cases c ON c.case_id=p.case_id
                WHERE c.case_ref=:caseRef
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 이 Case의 가장 최근 구매 제안(governance_action) 상태. */
    public String latestApprovalStatus(String caseRef) {
        return jdbc.sql("""
                SELECT ga.status::text FROM governance_actions ga JOIN cases c ON c.case_id=ga.case_id
                WHERE c.case_ref=:caseRef ORDER BY ga.governance_action_id DESC LIMIT 1
                """).param("caseRef", caseRef).query(String.class).single();
    }

    /** DB 전체에서 구매 신청(purchase_application)으로 이어진 발주 건수. 시나리오는 매번 빈 DB에서
     * 시작하므로 Case로 좁히지 않아도 이 Case의 발주만 남는다. */
    public long appliedPurchaseOrders() {
        return jdbc.sql("SELECT count(*) FROM purchase_orders WHERE purchase_application_id IS NOT NULL")
                .query(Long.class).single();
    }

    /** 위와 같은 범위에서 적용된 발주 품목의 금액 합계(KRW). */
    public BigDecimal appliedPurchaseTotal() {
        return jdbc.sql("""
                SELECT coalesce(sum(i.quantity*i.unit_price),0) FROM purchase_order_items i
                JOIN purchase_orders p USING(purchase_order_id) WHERE p.purchase_application_id IS NOT NULL
                """).query(BigDecimal.class).single();
    }

    /** 이 Case의 현재 상태(case_status). */
    public String caseStatus(String caseRef) {
        return jdbc.sql("SELECT status::text FROM cases WHERE case_ref=:caseRef")
                .param("caseRef", caseRef).query(String.class).single();
    }

    /** 이 Case에서 완료(DONE)로 끝난 SUPPLY_CHAIN Run 건수. 재시작 후에도 계획이 한 번만
     * 계산되었는지 확인하는 데 쓰인다(죽은 채로 남는 예전 Run은 outcome이 NULL이라 세지 않는다). */
    public long completedSupplyRuns(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM runs r JOIN agents a USING(agent_id) JOIN cases c ON c.case_id=r.case_id
                WHERE c.case_ref=:caseRef AND a.agent_key='SUPPLY_CHAIN' AND r.outcome='DONE'
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 이 Case에 열려 있고 아직 구매 승인에 연결되지 않은(governance_action_id IS NULL) 주의 요청
     * 건수. 만료 후 사람에게 던진 일반 질의를 기다릴 때 쓴다. */
    public long openAttentionWithoutApproval(String caseRef) {
        return jdbc.sql("""
                SELECT count(*) FROM attention_requests a JOIN cases c ON c.case_id=a.case_id
                WHERE c.case_ref=:caseRef AND a.status='OPEN' AND a.governance_action_id IS NULL
                """).param("caseRef", caseRef).query(Long.class).single();
    }

    /** 이 Case에서 주어진 워커 ID가 실제로 청구(claim)한 Run 건수. runs.lease_owner는 claim()
     * 시점에 채워진다(V21__execution_and_idempotency.sql). 재시작 뒤 새 워커가 실제로 뭔가를
     * 넘겨받았는지 — 즉 재시작이 헛돌지 않았는지 — 확인하는 데 쓴다. */
    public long runsClaimedBy(String caseRef, String workerId) {
        return jdbc.sql("""
                SELECT count(*) FROM runs r JOIN cases c ON c.case_id=r.case_id
                WHERE c.case_ref=:caseRef AND r.lease_owner=:workerId
                """).param("caseRef", caseRef).param("workerId", workerId).query(Long.class).single();
    }

    /** 이 Case에서 실행된 모든 Run(agent_key, run_ref, status, outcome), Run 순서대로. UAT 증거의
     * "Run별 결과" 항목이 여기서 나온다 — 러너의 model_finished 로그(비용·토큰·실패 코드)는
     * run_ref로 이 목록의 각 행과 합친다. */
    public List<RunRecord> runsForCase(String caseRef) {
        return jdbc.sql("""
                SELECT a.agent_key, r.run_ref, r.status::text AS status, r.outcome
                FROM runs r JOIN agents a USING(agent_id) JOIN cases c ON c.case_id=r.case_id
                WHERE c.case_ref=:caseRef ORDER BY r.run_id
                """).param("caseRef", caseRef)
                .query((rs, rowNum) -> new RunRecord(rs.getString("agent_key"), rs.getString("run_ref"),
                        rs.getString("status"), rs.getString("outcome")))
                .list();
    }

    /** 이 Case의 Run 한 건: 에이전트, run_ref, DB 상태, 완료 결과(outcome, 아직 끝나지 않았으면 null). */
    public record RunRecord(String agentKey, String runRef, String status, String outcome) {}
}
