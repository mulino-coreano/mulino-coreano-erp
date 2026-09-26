package com.mulinocoreano.backend.scenario;

import io.cucumber.spring.ScenarioScope;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class ScenarioWorld {
    @LocalServerPort int port;
    String caseRef;
    HumanChannel.ToolResult lastDecision;

    String apiBase() { return "http://127.0.0.1:" + port + "/api/v1"; }
}
