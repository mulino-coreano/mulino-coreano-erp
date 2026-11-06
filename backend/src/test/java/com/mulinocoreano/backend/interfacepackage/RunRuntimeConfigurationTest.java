package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunRuntimeConfigurationTest {
    @Test void deploymentSelectsEitherSupportedRuntimeAndRejectsInvalidValuesBeforeScheduling() {
        assertThat(new RunService(null,null,null,"CODEX").defaultRuntime()).isEqualTo("CODEX");
        assertThat(new RunService(null,null,null,"CLAUDE").defaultRuntime()).isEqualTo("CLAUDE");
        for (String value : new String[]{"GPT","","claude"})
            assertThatThrownBy(() -> new RunService(null,null,null,value)).isInstanceOf(IllegalArgumentException.class);
    }
}
