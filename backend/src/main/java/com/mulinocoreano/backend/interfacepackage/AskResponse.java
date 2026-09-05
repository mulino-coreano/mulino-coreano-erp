package com.mulinocoreano.backend.interfacepackage;

import java.util.List;

public record AskResponse(
        String answer,
        String intent,
        String query,
        List<InventoryDto> inventory,
        long totalLocationCount,
        int returnedLocationCount,
        boolean truncated,
        String provenance
) {}
