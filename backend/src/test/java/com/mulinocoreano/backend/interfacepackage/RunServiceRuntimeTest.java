package com.mulinocoreano.backend.interfacepackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 서버가 예약하는 Run의 런타임은 배포 설정이며 지원 목록 밖의 값으로는 시작하지 않는다. */
class RunServiceRuntimeTest {
    @Test
    void configuredRuntimeBecomesTheDefault() {
        assertThat(new RunService(null, null, null, "CLAUDE").defaultRuntime()).isEqualTo("CLAUDE");
        assertThat(new RunService(null, null, null).defaultRuntime()).isEqualTo("CODEX");
    }

    @Test
    void unsupportedRuntimeIsRejectedAtStartup() {
        assertThatThrownBy(() -> new RunService(null, null, null, "GPT"))
                .isInstanceOf(IllegalStateException.class);
    }
}
