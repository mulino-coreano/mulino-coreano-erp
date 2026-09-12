package com.mulinocoreano.backend.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.planning.PlanningSnapshotRepository;
import com.mulinocoreano.backend.planning.ReplenishmentCalculator;
import com.mulinocoreano.backend.planning.ReplenishmentDemoFixture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=purchase_bundle_it",
            "spring.datasource.hikari.schema=purchase_bundle_it",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public"
        })
@Transactional(isolation = Isolation.REPEATABLE_READ)
class PurchaseBundleAssemblerIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired PlanningSnapshotRepository snapshots;
    @Autowired ReplenishmentCalculator calculator;
    @Autowired PurchaseBundleAssembler assembler;
    @Autowired CanonicalJson json;

    @Test
    void actualPlanningFixtureProducesOneSupplierOrderFor16500WithoutWritingErp() throws Exception {
        ReplenishmentDemoFixture.load(jdbc);
        long warehouse =
                jdbc.sql("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'")
                        .query(Long.class)
                        .single();
        var products =
                jdbc.sql(
                                "SELECT product_id FROM products WHERE sku IN"
                                    + " ('DEMO-AMR','DEMO-BSC') ORDER BY product_id")
                        .query(Long.class)
                        .list();
        var source = snapshots.load(warehouse, products, LocalDate.of(2026, 9, 5), 30);
        var calculation = calculator.calculate(source);
        String before = erpState();

        var result =
                assembler.assemble(
                        "PLAN-BUNDLE-FIXTURE",
                        1,
                        "1".repeat(64),
                        json.sha256(source),
                        source,
                        calculation);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.orders()).hasSize(1);
        var order = result.orders().getFirst();
        assertThat(order.supplierName()).isEqualTo("DEMO 신속 공급");
        assertThat(order.expectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(order.lines()).hasSize(3);
        assertThat(order.lines())
                .extracting(PurchaseBundle.Line::buyQuantity)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("60"));
        assertThat(order.lines())
                .extracting(PurchaseBundle.Line::baseQuantity)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("60"));
        assertThat(order.lines())
                .extracting(PurchaseBundle.Line::buyUnit)
                .containsExactly("KG", "KG", "EA");
        assertThat(order.lines())
                .extracting(PurchaseBundle.Line::baseUnitPrice)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("1200"), new BigDecimal("1500"), new BigDecimal("50"));
        assertThat(order.lines())
                .extracting(PurchaseBundle.Line::lineAmountKrw)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("6000"), new BigDecimal("7500"), new BigDecimal("3000"));
        assertThat(result.totalKrw()).isEqualByComparingTo("16500");
        assertThat(order.totalKrw()).isEqualByComparingTo(result.totalKrw());
        assertThat(result.sourceHash()).isEqualTo(json.sha256(source));
        assertThat(result.asOf()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(result.targetDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(json.sha256(result))
                .isEqualTo(
                        json.sha256(
                                assembler.assemble(
                                        "PLAN-BUNDLE-FIXTURE",
                                        1,
                                        "1".repeat(64),
                                        json.sha256(source),
                                        source,
                                        calculation)));
        assertThat(erpState()).isEqualTo(before);
    }

    private String erpState() {
        return jdbc.sql(
                        """
SELECT jsonb_build_object(
    'po',(SELECT jsonb_agg(to_jsonb(p) ORDER BY purchase_order_id) FROM purchase_orders p),
    'items',(SELECT jsonb_agg(to_jsonb(i) ORDER BY purchase_order_item_id) FROM purchase_order_items i),
    'stock',(SELECT jsonb_agg(to_jsonb(s) ORDER BY stock_id) FROM stock s),
    'raw',(SELECT jsonb_agg(to_jsonb(r) ORDER BY raw_material_lot_id) FROM raw_material_lots r))::text
""")
                .query(String.class)
                .single();
    }
}
