package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 5 증거: UAT는 Run별 결과(에이전트, 상태, 완료 결과, 실패 코드, 비용·토큰)와 그 합계를
 * 남기고, 준비되지 않은 환경에서는 모델을 부르지 않는다. */
class UatEvidenceTest {
    ObjectMapper m = new ObjectMapper();

    @Test
    void perRunEntryCarriesOutcomeFailureAndUsageMatchedByRunRef() throws Exception {
        var dbRuns = List.of(
                new BusinessState.RunRecord("SUPPLY_CHAIN", "RUN-1", "COMPLETED", "DONE"),
                new BusinessState.RunRecord("ORCHESTRATOR", "RUN-2", "COMPLETED", "FAILED"));
        var modelFinished = List.of(
                m.readTree("{\"event\":\"model_finished\",\"runRef\":\"RUN-1\",\"costUsd\":0.12,\"inputTokens\":100,\"outputTokens\":20}"),
                m.readTree("{\"event\":\"model_finished\",\"runRef\":\"RUN-2\",\"failure\":\"MODEL_OUTPUT_TOO_LARGE\",\"costUsd\":0.05}"));

        var s = UatEvidence.summarize(dbRuns, modelFinished);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) s.get("runs");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).containsEntry("agentKey", "SUPPLY_CHAIN").containsEntry("runRef", "RUN-1")
                .containsEntry("outcome", "DONE").containsEntry("failure", null);
        assertThat((java.math.BigDecimal) runs.get(0).get("costUsd")).isEqualByComparingTo("0.12");
        assertThat(runs.get(1)).containsEntry("agentKey", "ORCHESTRATOR").containsEntry("runRef", "RUN-2")
                .containsEntry("outcome", "FAILED").containsEntry("failure", "MODEL_OUTPUT_TOO_LARGE");

        assertThat((java.math.BigDecimal) s.get("costUsd")).isEqualByComparingTo("0.17");
        assertThat(runs.get(0).get("inputTokens")).isEqualTo(100L);
        assertThat(s.get("inputTokens")).isNull();
        assertThat(s.get("failures")).isEqualTo(List.of("MODEL_OUTPUT_TOO_LARGE"));
    }

    @Test
    void runWithoutAModelFinishedLogRetainsUnknownUsage() {
        var dbRuns = List.of(new BusinessState.RunRecord("SUPPLY_CHAIN", "RUN-3", "QUEUED", null));

        var s = UatEvidence.summarize(dbRuns, List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) s.get("runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0)).containsEntry("outcome", null).containsEntry("failure", null)
                .containsEntry("costUsd", null).containsEntry("inputTokens",null).containsEntry("usageReported",false);
        assertThat(s.get("failures")).isEqualTo(List.of());
    }

    @Test
    void missingPrerequisitesAreNamedSoNoModelIsCalled() {
        assertThat(UatEvidence.prerequisitesMissing(Map.of("MULINO_AGENT_RUNTIME", "CLAUDE")))
                .containsExactlyInAnyOrder("MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
        assertThat(UatEvidence.summarize(List.of(),List.of()).get("usageComplete")).isEqualTo(false);
    }
    @Test
    void evidenceFileRetainsTheRunAndFinalBusinessState(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var record=new java.util.LinkedHashMap<String,Object>();
        record.put("testCase","TC-P2P-001");record.put("runtime","CLAUDE");record.put("model","claude-sonnet-5");
        record.putAll(UatEvidence.summarize(List.of(new BusinessState.RunRecord("PROCUREMENT","RUN-1","COMPLETED","WAITING")),
            List.of(m.readTree("{\"runRef\":\"RUN-1\",\"runtime\":\"CLAUDE\",\"model\":\"claude-sonnet-5\",\"resolvedModel\":\"claude-sonnet-5\",\"costUsd\":0.12,\"inputTokens\":20,\"outputTokens\":8}"))));
        record.put("caseStatus","WAITING");record.put("appliedPurchaseOrders",0);
        var persisted=m.readTree(java.nio.file.Files.readString(UatEvidence.write(m,dir,"TC-P2P-001",record)));
        assertThat(persisted.path("runs").get(0).path("outcome").asText()).isEqualTo("WAITING");
        assertThat(persisted.path("runs").get(0).path("resolvedModel").asText()).isEqualTo("claude-sonnet-5");
        assertThat(persisted.path("costUsd").decimalValue()).isEqualByComparingTo("0.12");
        assertThat(persisted.path("caseStatus").asText()).isEqualTo("WAITING");
        assertThat(persisted.path("appliedPurchaseOrders").asLong()).isZero();
    }

    @Test
    void terminalCancellationCannotInventZeroUsageOrACompleteCostTotal() throws Exception {
        var rows=List.of(new BusinessState.RunRecord("ORCHESTRATOR","RUN-1","COMPLETED","WAITING"),
            new BusinessState.RunRecord("PROCUREMENT","RUN-2","COMPLETED","WAITING"));
        var summary=UatEvidence.summarize(rows,List.of(
            m.readTree("{\"runRef\":\"RUN-1\",\"costUsd\":0.12,\"inputTokens\":20,\"outputTokens\":8}"),
            m.readTree("{\"runRef\":\"RUN-2\",\"runtime\":\"CLAUDE\",\"model\":\"claude-sonnet-5\",\"failure\":\"TERMINAL_FINALIZATION_TIMEOUT\"}")));
        assertThat(summary.get("costUsd")).isNull();
        assertThat((java.math.BigDecimal)summary.get("partialCostUsd")).isEqualByComparingTo("0.12");
        assertThat(summary.get("usageComplete")).isEqualTo(false);
        assertThat(summary.get("inputTokens")).isNull();
        assertThat(UatEvidence.completeUsage(m.readTree("{\"failure\":\"TERMINAL_FINALIZATION_TIMEOUT\"}"))).isFalse();
    }

    /** Codex turn.completed는 input·output·cache read 토큰만 보고하고 USD 비용·실제 모델은 보고하지 않는다.
     * Codex Run도 보고 가능한 사용량을 모두 남기면 UAT를 끝낼 수 있어야 하고, 비용은 만들어 내지 않는다. */
    @Test
    void codexRunIsCompleteWithTheUsageCodexReportsWhileItsCostStaysUnknown() throws Exception {
        var codex=m.readTree("{\"runRef\":\"RUN-1\",\"runtime\":\"CODEX\",\"model\":\"gpt-5-codex\",\"inputTokens\":41,\"outputTokens\":13,\"cacheReadTokens\":7}");
        var summary=UatEvidence.summarize(List.of(new BusinessState.RunRecord("PROCUREMENT","RUN-1","COMPLETED","WAITING")),List.of(codex));

        assertThat(UatEvidence.completeUsage(codex)).isTrue();
        assertThat(summary.get("usageComplete")).isEqualTo(true);
        assertThat(summary.get("inputTokens")).isEqualTo(41L);
        assertThat(summary.get("cacheReadTokens")).isEqualTo(7L);
        assertThat(summary.get("costUsd")).isNull();
        assertThat(summary.get("partialCostUsd")).isNull();
        assertThat(summary.get("cacheWriteTokens")).isNull();
        assertThat(UatEvidence.completeUsage(m.readTree("{\"runtime\":\"CODEX\",\"model\":\"gpt-5-codex\",\"inputTokens\":41,\"outputTokens\":13}"))).isFalse();
        assertThat(UatEvidence.completeUsage(m.readTree("{\"runtime\":\"CLAUDE\",\"model\":\"claude-sonnet-5\",\"resolvedModel\":\"claude-sonnet-5\",\"inputTokens\":41,\"outputTokens\":13,\"cacheReadTokens\":7,\"cacheWriteTokens\":0}"))).isFalse();
    }

}
