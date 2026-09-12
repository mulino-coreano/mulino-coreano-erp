package com.mulinocoreano.backend.procurement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Immutable proposed PO content; no ERP order or approval is created by constructing it. */
public record PurchaseBundle(
        String status,
        String planRef,
        int version,
        String hash,
        String sourceHash,
        long warehouseId,
        LocalDate asOf,
        LocalDate targetDate,
        List<Order> orders,
        BigDecimal totalKrw) {
    public PurchaseBundle {
        orders = List.copyOf(orders);
    }

    public record Order(
            long supplierId,
            String supplierName,
            String currency,
            LocalDate expectedDeliveryDate,
            List<Line> lines,
            BigDecimal totalKrw) {
        public Order {
            lines = List.copyOf(lines);
        }
    }

    public record Line(
            long materialId,
            String materialName,
            long sourceTermId,
            String buyUnit,
            BigDecimal buyQuantity,
            BigDecimal buyUnitPrice,
            String baseUnit,
            BigDecimal baseQuantity,
            BigDecimal baseUnitsPerBuyUnit,
            BigDecimal baseUnitPrice,
            LocalDate needDate,
            LocalDate expectedDeliveryDate,
            BigDecimal lineAmountKrw) {}
}
