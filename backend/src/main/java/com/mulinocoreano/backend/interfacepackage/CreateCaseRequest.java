package com.mulinocoreano.backend.interfacepackage;

public record CreateCaseRequest(
        String objective,
        String intentType,   // defaults to ACT
        String channel        // CHAT | SLACK | EMAIL | DASHBOARD | API
) {}
