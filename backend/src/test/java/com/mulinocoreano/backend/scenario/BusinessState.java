package com.mulinocoreano.backend.scenario;

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
}
