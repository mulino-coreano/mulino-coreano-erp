package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/** 목표 외 안전장치: 시나리오는 스키마를 지우므로 폐기용 loopback DB에서만 돈다. */
class ScenarioGuardTest {
    @Test
    void onlyLoopbackScenarioDatabasesMayBeWiped() {
        assertThatCode(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://localhost:55432/mulino_scenario"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://db.example:5432/mulino_scenario"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable("jdbc:postgresql://localhost:5432/mulino_coreano"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ScenarioGuard.requireDisposable(null)).isInstanceOf(IllegalStateException.class);
    }
}
