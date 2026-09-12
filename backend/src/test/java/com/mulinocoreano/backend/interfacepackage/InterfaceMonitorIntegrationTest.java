package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InterfaceMonitorIntegrationTest {

    @Autowired
    InterfaceService service;

    @Autowired
    JdbcClient jdbc;

    @Test
    void futureWaitingCaseIsNotReportedAsAtRisk() {
        long before = service.monitor().casesAtRisk();
        long caseId = businessCase("WAITING");
        workItem(caseId, "WAITING", Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(service.monitor().casesAtRisk()).isEqualTo(before);
    }

    @Test
    void overdueUnresolvedWorkItemMakesItsCaseAtRisk() {
        long before = service.monitor().casesAtRisk();
        long caseId = businessCase("OPEN");
        workItem(caseId, "WAITING", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.monitor().casesAtRisk()).isEqualTo(before + 1);
    }

    @Test
    void openMaterialExceptionMakesItsCaseAtRiskWithoutAnOverdueWorkItem() {
        long before = service.monitor().casesAtRisk();
        long caseId = businessCase("OPEN");
        long workItemId = workItem(caseId, "WAITING", Instant.now().plus(1, ChronoUnit.DAYS));
        materialException(caseId, workItemId);

        assertThat(service.monitor().casesAtRisk()).isEqualTo(before + 1);
    }

    @Test
    void overdueWorkAndMaterialExceptionCountTheSameCaseOnce() {
        long before = service.monitor().casesAtRisk();
        long caseId = businessCase("IN_PROGRESS");
        long workItemId = workItem(caseId, "BLOCKED", Instant.now().minus(1, ChronoUnit.DAYS));
        materialException(caseId, workItemId);

        assertThat(service.monitor().casesAtRisk()).isEqualTo(before + 1);
    }

    @Test
    void resolvedAndClosedCasesAreExcludedFromRisk() {
        long before = service.monitor().casesAtRisk();
        long resolved = businessCase("RESOLVED");
        long closed = businessCase("CLOSED");
        long resolvedWork = workItem(resolved, "BLOCKED", Instant.now().minus(1, ChronoUnit.DAYS));
        long closedWork = workItem(closed, "BLOCKED", Instant.now().minus(1, ChronoUnit.DAYS));
        materialException(resolved, resolvedWork);
        materialException(closed, closedWork);

        assertThat(service.monitor().casesAtRisk()).isEqualTo(before);
    }

    private long businessCase(String status) {
        return jdbc.sql("""
                        INSERT INTO cases
                            (case_ref, title, objective, intent_type, status, resolved_at)
                        VALUES
                            (:caseRef, 'Monitor risk case', 'Measure real risk', 'ACT',
                             :status::case_status,
                             CASE WHEN :status IN ('RESOLVED', 'CLOSED')
                                  THEN CURRENT_TIMESTAMP ELSE NULL END)
                        RETURNING case_id
                        """)
                .param("caseRef", "CASE-" + shortId())
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long workItem(long caseId, String status, Instant dueAt) {
        return jdbc.sql("""
                        INSERT INTO work_items
                            (work_item_ref, case_id, title, status, due_at)
                        VALUES
                            (:workItemRef, :caseId, 'Monitor risk work',
                             :status::work_item_status, :dueAt)
                        RETURNING work_item_id
                        """)
                .param("workItemRef", "WI-" + shortId())
                .param("caseId", caseId)
                .param("status", status)
                .param("dueAt", Timestamp.from(dueAt))
                .query(Long.class)
                .single();
    }

    private void materialException(long caseId, long workItemId) {
        jdbc.sql("""
                        INSERT INTO attention_requests
                            (case_id, work_item_id, reason_type, title,
                             question, consequence, status)
                        VALUES
                            (:caseId, :workItemId, 'MATERIAL_EXCEPTION',
                             'Material exception', 'How should this be resolved?',
                             'The Case remains at risk.', 'OPEN')
                        """)
                .param("caseId", caseId)
                .param("workItemId", workItemId)
                .update();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
