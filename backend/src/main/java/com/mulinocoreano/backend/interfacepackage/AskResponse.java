package com.mulinocoreano.backend.interfacepackage;

import java.util.List;

public record AskResponse(
        String answer,
        String intent,
        List<InventoryDto> inventory,
        String provenance
) {}
