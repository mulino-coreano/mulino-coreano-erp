package com.mulinocoreano.backend.planning;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;
import static com.mulinocoreano.backend.planning.BomPlanner.*;
import static org.assertj.core.api.Assertions.*;

class ReplenishmentCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);
    private static final Item PRODUCT = new Item(Kind.PRODUCT, 1, "CASE");
    private static final Item FLOUR = new Item(Kind.MATERIAL, 2, "KG");
    private final ReplenishmentCalculator calculator = new ReplenishmentCalculator(new ForecastService(), new BomPlanner(), new SupplierSelectionService());

    @Test
    void connectsHistorySafetyBatchProductionAndOneBulkPurchaseWithoutWritingErp() {
        var snapshot = snapshot(true, "10");
        var result = calculator.calculate(snapshot);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.forecasts().get(1L).safetyQuantity()).isEqualByComparingTo("7");
        assertThat(result.requirements().production().stream().map(ProductionRequirement::productionQuantity).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("30");
        assertThat(result.purchases()).singleElement().satisfies(p -> {
            assertThat(p.material()).isEqualTo(FLOUR);
            assertThat(p.firstNeedDate()).isEqualTo(TODAY.plusDays(8));
            assertThat(p.netBaseQuantity()).isEqualByComparingTo("6");
            assertThat(p.selection().chosen().purchaseQuantity()).isEqualByComparingTo("6");
        });
        assertThat(result.totalAmount()).isEqualByComparingTo("6000");
        assertThat(result.issues()).isEmpty();
        assertThat(snapshot.supply().getFirst().quantity()).isEqualByComparingTo("10");
        assertThat(calculator.calculate(snapshot)).isEqualTo(result);
    }

    @Test
    void missingSupplierProducesAttentionAndDoesNotReportPartialTotalAsComplete() {
        var result = calculator.calculate(snapshot(false, "10"));
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("NEEDS_ATTENTION");
        assertThat(result.totalAmount()).isNull();
        assertThat(result.issues()).contains(new ReplenishmentCalculator.Issue("NO_ELIGIBLE_SUPPLIER", "raw_materials:2"));
    }

    @Test
    void sufficientFinishedStockCreatesNoPurchaseDespiteSupplierMinimum() {
        var result = calculator.calculate(snapshot(true, "100"));
        assertThat(result).isNotNull();
        assertThat(result.purchases()).isEmpty();
        assertThat(result.totalAmount()).isEqualByComparingTo("0");
        assertThat(result.requirements().production()).isEmpty();
    }

    private static PlanningSnapshotRepository.Snapshot snapshot(boolean supplier, String available) {
        var history = IntStream.rangeClosed(1, 56).mapToObj(day -> new ForecastService.HistoricalOrder(
                TODAY.minusDays(day), "SHIPPED", BigDecimal.ONE, "order_items:" + day)).toList();
        var input = new PlanningSnapshotRepository.ProductInput(PRODUCT, "AMR", "Amaretti", history, List.of());
        var bom = new Bom("bom_versions:1", PRODUCT, new BigDecimal("10"), 2, 180,
                TODAY.minusDays(60), TODAY.plusDays(180), List.of(new Component(FLOUR, new BigDecimal("2"))));
        var stock = new StockLot("production_lots:1", PRODUCT, new BigDecimal(available), TODAY.minusDays(1), TODAY.plusDays(180), null, false);
        var term = new SupplierSelectionService.SupplierTerm(1, 1, true, "KG", BigDecimal.ONE, "KG",
                new BigDecimal("1000"), "KRW", new BigDecimal("5"), new BigDecimal("2"), 1,
                TODAY.minusDays(1), TODAY.plusDays(180), Set.of());
        return new PlanningSnapshotRepository.Snapshot(1, TODAY, 30, 7, TODAY.minusDays(56),
                List.of(input), List.of(bom), List.of(stock), supplier ? Map.of(2L, List.of(term)) : Map.of(), List.of(), List.of());
    }
}
