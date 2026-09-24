package com.mulinocoreano.backend.interfacepackage;

import java.util.List;

public record MonitorDto(
        long casesOpen,
        long casesAtRisk,
        long workItemsReady,
        long workItemsWaiting,
        long attentionOpen,
        List<AttentionDto> attentionSamples
) {}
