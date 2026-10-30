package com.mulinocoreano.backend.interfacepackage;

import java.math.BigDecimal;

public record InventoryDto(
        String productName,
        String sku,
        BigDecimal quantity,
        String warehouseName
) {}
