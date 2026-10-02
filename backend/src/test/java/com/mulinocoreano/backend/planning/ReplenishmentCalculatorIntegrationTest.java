package com.mulinocoreano.backend.planning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional(isolation = Isolation.REPEATABLE_READ)
class ReplenishmentCalculatorIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired PlanningSnapshotRepository repository;
    @Autowired ReplenishmentCalculator calculator;

    @Test
    void completeErpFixtureProducesTheHandCalculatedPlanWithoutChangingTransactions() throws Exception {
        ReplenishmentDemoFixture.load(jdbc);
        long warehouse = id("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'");
        long amr = id("SELECT product_id FROM products WHERE sku='DEMO-AMR'");
        long bsc = id("SELECT product_id FROM products WHERE sku='DEMO-BSC'");
        long dough = id("SELECT product_id FROM products WHERE sku='DEMO-DOUGH'");
        var before = transactionState();

        var snapshot = repository.load(warehouse, List.of(amr, bsc), LocalDate.of(2026, 9, 5), 30);
        var result = calculator.calculate(snapshot);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(production(result, amr)).isEqualByComparingTo("50");
        assertThat(production(result, bsc)).isEqualByComparingTo("30");
        assertThat(production(result, dough)).isEqualByComparingTo("15");
        assertThat(result.forecasts().get(amr).safetyQuantity()).isEqualByComparingTo("14");
        assertThat(result.forecasts().get(bsc).safetyQuantity()).isEqualByComparingTo("7");
        assertThat(result.totalAmount()).isEqualByComparingTo("16500");
        assertThat(result.purchases()).hasSize(3);
        var net = result.purchases().stream().map(ReplenishmentCalculator.PurchaseRequirement::netBaseQuantity).toList();
        assertThat(net).usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("60"));
        assertThat(result.purchases()).allSatisfy(p -> assertThat(p.selection().feasibleCandidates()).hasSize(2));
        assertThat(snapshot.sourceFacts()).extracting(PlanningSnapshotRepository.SourceFact::sourceRef)
                .anyMatch(ref -> ref.startsWith("order_items:"))
                .anyMatch(ref -> ref.startsWith("production_product_inputs:"))
                .anyMatch(ref -> ref.startsWith("purchase_order_items:"));
        assertThat(result.requirements().allocations()).anySatisfy(allocation -> {
            assertThat(allocation.sourceRef()).startsWith("purchase_order_items:");
            assertThat(allocation.projected()).isTrue();
        });
        assertThat(transactionState()).isEqualTo(before);
        assertThat(repository.load(warehouse, List.of(bsc, amr), snapshot.asOf(), 30)).isEqualTo(snapshot);
    }

    private static BigDecimal production(ReplenishmentCalculator.Calculation result, long productId) {
        return result.requirements().production().stream().filter(p -> p.product().id() == productId)
                .map(BomPlanner.ProductionRequirement::productionQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private long id(String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private String transactionState() {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                    'stock',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stock_id) FROM stock s),
                    'raw',(SELECT jsonb_agg(to_jsonb(r) ORDER BY raw_material_lot_id) FROM raw_material_lots r),
                    'production',(SELECT jsonb_agg(to_jsonb(p) ORDER BY production_lot_id) FROM production_lots p),
                    'po',(SELECT count(*) FROM purchase_orders),
                    'po_items',(SELECT count(*) FROM purchase_order_items),
                    'cases',(SELECT count(*) FROM cases),
                    'plans',(SELECT count(*) FROM replenishment_plans),
                    'events',(SELECT count(*) FROM events))::text
                """).query(String.class).single();
    }
}
