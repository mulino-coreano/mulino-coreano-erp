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
        assertThat((double) runs.get(0).get("costUsd")).isEqualTo(0.12, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(runs.get(1)).containsEntry("agentKey", "ORCHESTRATOR").containsEntry("runRef", "RUN-2")
                .containsEntry("outcome", "FAILED").containsEntry("failure", "MODEL_OUTPUT_TOO_LARGE");

        assertThat((double) s.get("costUsd")).isEqualTo(0.17, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.get("inputTokens")).isEqualTo(100L);
        assertThat(s.get("failures")).isEqualTo(List.of("MODEL_OUTPUT_TOO_LARGE"));
    }

    @Test
    void runWithoutAModelFinishedLogStillAppearsWithZeroUsage() {
        var dbRuns = List.of(new BusinessState.RunRecord("SUPPLY_CHAIN", "RUN-3", "QUEUED", null));

        var s = UatEvidence.summarize(dbRuns, List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runs = (List<Map<String, Object>>) s.get("runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0)).containsEntry("outcome", null).containsEntry("failure", null)
                .containsEntry("costUsd", 0.0);
        assertThat(s.get("failures")).isEqualTo(List.of());
    }

    @Test
    void missingPrerequisitesAreNamedSoNoModelIsCalled() {
        assertThat(UatEvidence.prerequisitesMissing(Map.of("MULINO_AGENT_RUNTIME", "CLAUDE")))
                .containsExactlyInAnyOrder("MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
    }
}
