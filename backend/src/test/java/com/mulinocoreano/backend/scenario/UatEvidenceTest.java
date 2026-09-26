package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 5 증거: UAT는 실행마다 비용·토큰·실패 코드를 합산해 남기고, 준비되지 않은 환경에서는 모델을 부르지 않는다. */
class UatEvidenceTest {
    ObjectMapper m = new ObjectMapper();

    @Test
    void summarisesCostTokensAndFailuresAcrossRuns() throws Exception {
        var s = UatEvidence.summarize(List.of(
                m.readTree("{\"event\":\"model_finished\",\"costUsd\":0.12,\"inputTokens\":100,\"outputTokens\":20}"),
                m.readTree("{\"event\":\"model_finished\",\"failure\":\"MODEL_OUTPUT_TOO_LARGE\",\"costUsd\":0.05}")));
        assertThat(s.get("runs")).isEqualTo(2);
        assertThat((double) s.get("costUsd")).isEqualTo(0.17, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.get("inputTokens")).isEqualTo(100L);
        assertThat(s.get("failures")).isEqualTo(List.of("MODEL_OUTPUT_TOO_LARGE"));
    }

    @Test
    void missingPrerequisitesAreNamedSoNoModelIsCalled() {
        assertThat(UatEvidence.prerequisitesMissing(Map.of("MULINO_AGENT_RUNTIME", "CLAUDE")))
                .containsExactlyInAnyOrder("MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
    }
}
