package com.mulinocoreano.backend.scenario;

import io.cucumber.spring.ScenarioScope;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.stereotype.Component;

@Component
@ScenarioScope
public class ScenarioWorld {
    @LocalServerPort private int port;
    private String caseRef;
    private Long approvalId;
    private final java.util.List<java.util.Map<String,Object>> pendingEvidence = new java.util.ArrayList<>();

    int port() { return port; }
    String caseRef() { return caseRef; }
    void caseRef(String value) { caseRef = value; }
    Long approvalId() { return approvalId; }
    void approvalId(Long value) { approvalId = value; }
    java.util.List<java.util.Map<String,Object>> pendingEvidence() { return pendingEvidence; }

    String apiBase() { return "http://127.0.0.1:" + port + "/api/v1"; }
}
