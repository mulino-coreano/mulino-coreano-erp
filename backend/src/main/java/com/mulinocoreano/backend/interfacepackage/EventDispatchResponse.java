package com.mulinocoreano.backend.interfacepackage;

import java.util.List;

public record EventDispatchResponse(
        long eventId,
        List<String> satisfiedWaiting,
        List<String> readyWorkItems,
        List<String> scheduledRuns,
        List<String> failedRuns
) {
    public EventDispatchResponse {
        satisfiedWaiting = List.copyOf(satisfiedWaiting);
        readyWorkItems = List.copyOf(readyWorkItems);
        scheduledRuns = List.copyOf(scheduledRuns);
        failedRuns = List.copyOf(failedRuns);
    }
}
