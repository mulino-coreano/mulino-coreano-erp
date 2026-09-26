package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 4 보조: 에이전트가 기대 상태에 못 가면 멈추지 않고 이유와 함께 실패하며, 실행기는 반드시 정리된다. */
class AgentDriverTest {
    @Test
    void unreachedStateFailsWithinTheTimeoutAndTheChildIsKilled() {
        var driver = new AgentDriver(new ObjectMapper(),
                List.of("node", "-e", "console.log(JSON.stringify({event:'model_finished',failure:'MODEL_OUTPUT_TOO_LARGE'})); setInterval(()=>{},1000)"));
        driver.start(Map.of());
        assertThatThrownBy(() -> driver.awaitState("구매 제안 승인 대기", () -> false, Duration.ofMillis(800)))
                .hasMessageContaining("구매 제안 승인 대기").hasMessageContaining("MODEL_OUTPUT_TOO_LARGE");
        driver.stop();
        assertThat(driver.isAlive()).isFalse();
    }
}
