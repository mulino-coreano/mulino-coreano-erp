package com.mulinocoreano.backend.interfacepackage;

import java.time.Instant;

public record RunDto(
        long runId,
        String runRef,
        String agentKey,
        String status,
        Instant startedAt
) {}
