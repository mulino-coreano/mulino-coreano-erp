package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReplenishmentDemoFixtureIntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void reproducesHistoryAndReconcilesAllPhysicalAndUsableSupplies() throws Exception {
        ReplenishmentDemoFixture.load(jdbc);
        assertThat(jdbc.sql("SELECT count(DISTINCT order_date) FROM orders WHERE status='SHIPPED'").query(Integer.class).single()).isEqualTo(56);
        assertThat(jdbc.sql("SELECT sum(oi.quantity) FROM order_items oi JOIN products p USING(product_id) WHERE p.sku='DEMO-AMR'").query(BigDecimal.class).single()).isEqualByComparingTo("112");
        assertThat(jdbc.sql("SELECT sum(oi.quantity) FROM order_items oi JOIN products p USING(product_id) WHERE p.sku='DEMO-BSC'").query(BigDecimal.class).single()).isEqualByComparingTo("56");
        assertThat(jdbc.sql("SELECT count(*) FROM outbound o WHERE quantity <> (SELECT sum(lot_quantity) FROM outbound_lots ol WHERE ol.outbound_id=o.outbound_id)").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM stock s WHERE s.quantity <> (
                    SELECT sum(pl.quantity
                        - COALESCE((SELECT sum(ol.lot_quantity) FROM outbound_lots ol WHERE ol.lot_id=pl.production_lot_id),0)
                        - COALESCE((SELECT sum(i.quantity_used) FROM production_product_inputs i WHERE i.source_production_lot_id=pl.production_lot_id),0))
                    FROM production_lots pl WHERE pl.product_id=s.product_id AND pl.warehouse_id=s.warehouse_id)
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM raw_material_lots r WHERE remaining_quantity <> quantity - COALESCE((SELECT sum(quantity_used) FROM production_ingredients i WHERE i.raw_material_lot_id=r.raw_material_lot_id),0)").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT sum(quantity) FROM production_lots pl JOIN products p USING(product_id)
                WHERE p.sku='DEMO-AMR' AND pl.status='ACTIVE' AND expiry_date >= '2026-09-05'
                """).query(BigDecimal.class).single()).isEqualByComparingTo("30");
        assertThat(jdbc.sql("""
                SELECT sum(r.remaining_quantity) FROM raw_material_lots r JOIN raw_materials m USING(raw_material_id)
                JOIN inbound i USING(inbound_id) WHERE m.name='DEMO 밀가루' AND i.status='RELEASED'
                """).query(BigDecimal.class).single()).isEqualByComparingTo("3");
        assertThat(jdbc.sql("SELECT sum(quantity-received_quantity) FROM purchase_order_items i JOIN purchase_orders p USING(purchase_order_id) WHERE p.status='ORDERED' AND p.expected_delivery_date='2026-09-07'").query(BigDecimal.class).single()).isEqualByComparingTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM external_identities i JOIN users u USING(user_id) WHERE u.email='replenishment-demo@example.invalid'").query(Integer.class).single()).isZero();
    }
}
