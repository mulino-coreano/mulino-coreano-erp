package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional(isolation = Isolation.REPEATABLE_READ)
class PlanningSnapshotRepositoryIntegrationTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    @Autowired JdbcClient jdbc;
    @Autowired PlanningSnapshotRepository repository;
    long warehouse;
    long amr;
    long bsc;

    @BeforeEach
    void fixture() throws Exception {
        ReplenishmentDemoFixture.load(jdbc);
        warehouse = id("SELECT warehouse_id FROM warehouses WHERE plant_id='DEMO-KR-01'");
        amr = id("SELECT product_id FROM products WHERE sku='DEMO-AMR'");
        bsc = id("SELECT product_id FROM products WHERE sku='DEMO-BSC'");
    }

    @Test
    void loadsCoherentMultilevelSnapshotWithPhysicalAndProjectedSources() {
        var snapshot = repository.load(warehouse, List.of(bsc, amr, amr), AS_OF, 30);
        assertThat(snapshot.products()).extracting(PlanningSnapshotRepository.ProductInput::sku)
                .containsExactly("DEMO-AMR", "DEMO-BSC");
        assertThat(snapshot.products()).allSatisfy(product -> assertThat(product.history()).hasSize(56));
        assertThat(snapshot.boms()).hasSize(3);
        assertThat(snapshot.safetyDays()).isEqualTo(7);
        assertThat(snapshot.historyStartDate()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(snapshot.supplierTerms()).hasSize(3);
        assertThat(snapshot.certificates()).hasSize(6);
        assertThat(snapshot.supply().stream().filter(BomPlanner.StockLot::projected).toList())
                .singleElement().satisfies(lot -> {
                    assertThat(lot.quantity()).isEqualByComparingTo("2");
                    assertThat(lot.availableOn()).isEqualTo(LocalDate.of(2026, 9, 7));
                });
        BigDecimal amrPhysical = snapshot.supply().stream()
                .filter(lot -> lot.item().kind() == BomPlanner.Kind.PRODUCT && lot.item().id() == amr)
                .map(BomPlanner.StockLot::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(amrPhysical).isEqualByComparingTo("40");
        assertThat(snapshot.sourceFacts()).isNotEmpty();
    }

    @Test
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void rejectsCallerTransactionWithoutSnapshotIsolation() {
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INCOHERENT_SNAPSHOT_TRANSACTION");
    }

    @Test
    void sourceFactsPreserveCanonicalNamesTypesAndOrdering() {
        var snapshot = load();
        assertThat(snapshot.sourceFacts()).extracting(PlanningSnapshotRepository.SourceFact::sourceRef)
                .isSorted().doesNotHaveDuplicates();
        snapshot.sourceFacts().forEach(fact -> {
            assertThat(fact.values().keySet().stream().toList()).isSorted();
            fact.values().values().forEach(value -> assertThat(value == null
                    || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof List<?>).isTrue());
        });
        var product = snapshot.sourceFacts().stream()
                .filter(fact -> fact.sourceRef().equals("products:" + amr)).findFirst().orElseThrow();
        assertThat(product.values()).containsEntry("product_type", "FINISHED_GOODS")
                .containsEntry("is_active", true);
        assertThat(product.values().keySet()).containsExactly("expiry_days", "is_active", "name",
                "product_id", "product_type", "sku", "unit");
        var receipts = snapshot.sourceFacts().stream()
                .filter(fact -> fact.sourceRef().startsWith("inbound:")).toList();
        assertThat(receipts).isNotEmpty().allSatisfy(fact -> {
            var original = jdbc.sql("SELECT * FROM inbound WHERE inbound_id=:id")
                    .param("id", fact.values().get("inbound_id")).query().singleRow();
            for (String column : List.of("inbound_date", "expiry_date", "status", "status_decided_at")) {
                Object value = original.get(column);
                assertThat(fact.values().get(column)).isEqualTo(value == null ? null : value.toString());
            }
        });
        var terms = snapshot.sourceFacts().stream()
                .filter(fact -> fact.sourceRef().startsWith("supplier_material_terms:")).toList();
        assertThat(terms).isNotEmpty().allSatisfy(fact -> {
            assertThat(fact.values().get("unit_price")).isInstanceOf(BigDecimal.class);
            assertThat(fact.values().get("required_cert_types")).isInstanceOf(List.class);
            assertThat((List<?>) fact.values().get("required_cert_types"))
                    .isNotEmpty().allSatisfy(type -> assertThat(type).isInstanceOf(String.class));
            assertThat(fact.values()).containsKeys("supplier_active", "supplier_name");
            assertThat(fact.values().get("valid_from")).isInstanceOf(String.class);
            assertThat(LocalDate.parse((String) fact.values().get("valid_from"))).isNotNull();
        });
        assertThat(snapshot.supplierTerms().values()).allSatisfy(materialTerms ->
                assertThat(materialTerms).allSatisfy(term ->
                        assertThat(term.requiredCertificateTypes()).isNotEmpty()));
        assertThat(snapshot.sourceFacts().stream().filter(fact -> fact.sourceRef().startsWith("outbound_lots:")))
                .allSatisfy(fact -> assertThat(fact.values())
                        .containsKeys("lot_product_id", "lot_warehouse_id", "lot_quantity"));
    }

    private long id(String query) { return jdbc.sql(query).query(Long.class).single(); }

    @Test void purchaseLineArrivalTakesPrecedenceOverTheHeaderFinalArrival() {
        jdbc.sql("UPDATE purchase_orders SET expected_delivery_date=DATE '2026-09-20' WHERE status='ORDERED'").update();
        jdbc.sql("""
                UPDATE purchase_order_items i SET purchase_quantity=i.quantity,purchase_unit_price=i.unit_price,
                  purchase_unit=t.purchase_unit,base_unit=r.unit,base_quantity_per_purchase_unit=1,
                  line_amount=round(i.quantity*i.unit_price,0),expected_delivery_date=DATE '2026-09-07',
                  supplier_material_term_id=t.supplier_material_term_id
                FROM purchase_orders p,supplier_material_terms t,raw_materials r
                WHERE i.purchase_order_id=p.purchase_order_id AND p.status='ORDERED'
                  AND t.supplier_id=p.supplier_id AND t.raw_material_id=i.raw_material_id AND r.raw_material_id=i.raw_material_id
                """).update();
        assertThat(load().supply().stream().filter(BomPlanner.StockLot::projected).toList())
                .singleElement().satisfies(lot->assertThat(lot.availableOn()).isEqualTo(LocalDate.of(2026,9,7)));
    }

    @Test void purchaseAssignedToAnotherWarehouseIsNotSupplyForThisPlan() {
        long other=id("INSERT INTO warehouses(name,type) VALUES('다른 목적지','AMBIENT') RETURNING warehouse_id");
        jdbc.sql("UPDATE purchase_orders SET warehouse_id=:warehouse WHERE status='ORDERED'").param("warehouse",other).update();
        assertThat(load().supply().stream().filter(BomPlanner.StockLot::projected).toList()).isEmpty();
    }

    @Test
    void rejectsStockThatDisagreesWithLotResiduals() {
        jdbc.sql("UPDATE stock SET quantity=quantity+1 WHERE product_id=:id").param("id", amr).update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("STOCK_LOT_MISMATCH");
    }

    @Test
    void rejectsPositiveProductLotWithoutKnownWarehouse() {
        jdbc.sql("UPDATE production_lots SET warehouse_id=NULL WHERE lot_number='DEMO-AMR-CURRENT'").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MISSING_LOT_WAREHOUSE");
    }

    @Test
    void rejectsProductConsumptionAboveOriginalQuantity() {
        jdbc.sql("UPDATE production_product_inputs SET quantity_used=quantity_used+100 WHERE production_product_input_id=(SELECT min(production_product_input_id) FROM production_product_inputs)").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PRODUCT_LOT_OVERCONSUMED");
    }

    @Test
    void rejectsRawResidualMismatch() {
        jdbc.sql("UPDATE raw_material_lots SET remaining_quantity=remaining_quantity-1 WHERE lot_number='DEMO-RM-FLOUR'").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_LOT_REMAINING_MISMATCH");
    }

    @Test
    void rejectsOutboundWhoseLotAllocationsDoNotMatchShipment() {
        jdbc.sql("UPDATE outbound SET quantity=quantity+1 WHERE outbound_id=(SELECT min(outbound_id) FROM outbound)").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OUTBOUND_LOT_MISMATCH");
    }

    @Test
    void preservesHeldAndExpiredStockWithoutOfferingItAsUsableSupply() {
        var snapshot = load();
        long flour = id("SELECT raw_material_id FROM raw_materials WHERE name='DEMO 밀가루'");
        assertThat(snapshot.supply().stream().filter(lot -> lot.item().kind() == BomPlanner.Kind.MATERIAL
                && lot.item().id() == flour && !lot.projected()).toList()).hasSize(2)
                .anySatisfy(lot -> {
                    assertThat(lot.exclusionReason()).isEqualTo("INBOUND_HOLD");
                    assertThat(lot.quantity()).isEqualByComparingTo("2");
                }).anySatisfy(lot -> {
                    assertThat(lot.exclusionReason()).isNull();
                    assertThat(lot.quantity()).isEqualByComparingTo("3");
                });
        assertThat(snapshot.supply()).anySatisfy(lot -> {
            assertThat(lot.exclusionReason()).isEqualTo("PRODUCT_QUARANTINE");
            assertThat(lot.quantity()).isEqualByComparingTo("5");
        }).anySatisfy(lot -> {
            assertThat(lot.expiresOn()).isEqualTo(AS_OF.minusDays(1));
            assertThat(lot.quantity()).isEqualByComparingTo("5");
        });
    }

    @Test
    void delayedPurchaseIsExcludedAndReceiptQuantityMustReconcile() {
        jdbc.sql("UPDATE purchase_orders SET expected_delivery_date='2026-09-04' WHERE status='ORDERED'").update();
        assertThat(load().supply()).noneMatch(BomPlanner.StockLot::projected);
        jdbc.sql("UPDATE purchase_order_items SET received_quantity=1 WHERE purchase_order_id IN (SELECT purchase_order_id FROM purchase_orders WHERE status='ORDERED')").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PO_RECEIPT_MISMATCH");
    }

    @Test
    void confirmedOrderAggregatesDuplicateProductLinesBeforeSubtractingShipments() {
        long order = id("""
                INSERT INTO orders(customer_id,created_by,order_date,expected_delivery_date,status)
                SELECT customer_id,(SELECT user_id FROM users LIMIT 1),'2026-09-05','2026-09-10','CONFIRMED'
                FROM customers LIMIT 1 RETURNING order_id
                """);
        jdbc.sql("INSERT INTO order_items(order_id,product_id,quantity,unit_price) VALUES (:order,:product,4,4000),(:order,:product,6,4000)")
                .param("order", order).param("product", amr).update();
        long outbound = jdbc.sql("INSERT INTO outbound(product_id,warehouse_id,order_id,quantity,outbound_date) VALUES (:product,:warehouse,:order,3,'2026-09-05') RETURNING outbound_id")
                .param("product", amr).param("warehouse", warehouse).param("order", order).query(Long.class).single();
        jdbc.sql("INSERT INTO outbound_lots(outbound_id,lot_id,lot_quantity) SELECT :outbound,production_lot_id,3 FROM production_lots WHERE lot_number='DEMO-AMR-CURRENT'")
                .param("outbound", outbound).update();
        jdbc.sql("UPDATE stock SET quantity=quantity-3 WHERE product_id=:product").param("product", amr).update();
        assertThat(load().products().stream().filter(product -> product.item().id() == amr).findFirst().orElseThrow().openOrders())
                .singleElement().satisfies(row -> {
                    assertThat(row.quantity()).isEqualByComparingTo("10");
                    assertThat(row.shippedQuantity()).isEqualByComparingTo("3");
                    assertThat(row.dueDate()).isEqualTo(LocalDate.of(2026, 9, 10));
                });
    }

    private PlanningSnapshotRepository.Snapshot load() { return repository.load(warehouse, List.of(amr, bsc), AS_OF, 30); }

    @Test
    void requiresPolicyActiveFinishedGoodsAndRecognizedUnit() {
        long dough = id("SELECT product_id FROM products WHERE sku='DEMO-DOUGH'");
        assertThatThrownBy(() -> repository.load(warehouse, List.of(dough), AS_OF, 30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FINISHED_GOODS_REQUIRED");
        jdbc.sql("UPDATE products SET unit='unknown' WHERE product_id=:product").param("product", amr).update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UNRECOGNIZED_UNIT");
    }

    @Test
    void inactiveRequestedProductAndMissingPolicyFailExplicitly() {
        jdbc.sql("UPDATE products SET is_active=false WHERE product_id=:product").param("product", amr).update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INACTIVE_PRODUCT");
        jdbc.sql("UPDATE products SET is_active=true WHERE product_id=:product").param("product", amr).update();
        jdbc.sql("DELETE FROM planning_policies").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MISSING_PLANNING_POLICY");
    }

    @Test
    void rejectsCurrentRawLotWhoseInboundPointsAtUnrelatedPurchaseItem() {
        long unrelated = id("INSERT INTO raw_materials(name,unit,supplier_id) SELECT 'DEMO unrelated','KG',supplier_id FROM suppliers WHERE name='DEMO 신속 공급' RETURNING raw_material_id");
        long inbound = id("SELECT inbound_id FROM raw_material_lots WHERE lot_number='DEMO-RM-FLOUR'");
        long oldItem = jdbc.sql("SELECT purchase_order_item_id FROM inbound WHERE inbound_id=:id").param("id", inbound).query(Long.class).single();
        long newItem = jdbc.sql("""
                INSERT INTO purchase_order_items(purchase_order_id,raw_material_id,quantity,received_quantity,unit_price)
                SELECT purchase_order_id,:material,quantity,received_quantity,unit_price
                FROM purchase_order_items WHERE purchase_order_item_id=:id RETURNING purchase_order_item_id
                """).param("material", unrelated).param("id", oldItem).query(Long.class).single();
        jdbc.sql("UPDATE purchase_order_items SET received_quantity=0 WHERE purchase_order_item_id=:id").param("id", oldItem).update();
        jdbc.sql("UPDATE inbound SET purchase_order_item_id=:item WHERE inbound_id=:id").param("item", newItem).param("id", inbound).update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_LOT_IDENTITY_MISMATCH");
    }

    @Test
    void acceptsCoherentPurchaseFromAlternativeSupplier() {
        long supplier = id("SELECT supplier_id FROM suppliers WHERE name='DEMO 대량 공급'");
        jdbc.sql("UPDATE purchase_orders SET supplier_id=:supplier WHERE status='COMPLETED'").param("supplier", supplier).update();
        jdbc.sql("""
                UPDATE inbound SET supplier_id=:supplier WHERE purchase_order_item_id IN (
                    SELECT purchase_order_item_id FROM purchase_order_items JOIN purchase_orders USING(purchase_order_id)
                    WHERE status='COMPLETED')
                """).param("supplier", supplier).update();
        assertThat(load().supply()).anySatisfy(lot -> {
            assertThat(lot.item().kind()).isEqualTo(BomPlanner.Kind.MATERIAL);
            assertThat(lot.quantity()).isEqualByComparingTo("3");
            assertThat(lot.exclusionReason()).isNull();
        });
    }

    @Test
    void rejectsRawLotSupplyInventedBeyondRecordedReceipt() {
        jdbc.sql("UPDATE raw_material_lots SET quantity=quantity+100,remaining_quantity=remaining_quantity+100 WHERE lot_number='DEMO-RM-FLOUR'").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_RECEIPT_LOT_MISMATCH");
    }

    @Test
    void rejectsReceiptWithPartiallyAllocatedLotQuantity() {
        jdbc.sql("UPDATE raw_material_lots SET quantity=quantity-1,remaining_quantity=remaining_quantity-1 WHERE lot_number='DEMO-RM-FLOUR'").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_RECEIPT_LOT_MISMATCH");
    }

    @Test
    void rejectsReceiptWithoutAnyAllocatedLot() {
        jdbc.sql("DELETE FROM raw_material_lots WHERE lot_number='DEMO-RM-FLOUR-HOLD'").update();
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_RECEIPT_LOT_MISMATCH");
    }

    @Test
    void acceptsReceiptCompletelyAllocatedAcrossSeveralLots() {
        splitFlourLot(null);
        long flour = id("SELECT raw_material_id FROM raw_materials WHERE name='DEMO 밀가루'");
        BigDecimal available = load().supply().stream()
                .filter(lot -> lot.item().kind() == BomPlanner.Kind.MATERIAL && lot.item().id() == flour
                        && !lot.projected() && lot.exclusionReason() == null)
                .map(BomPlanner.StockLot::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(available).isEqualByComparingTo("3");
    }

    @Test
    void rejectsSiblingLotAllocatedToUnrelatedMaterialEvenWhenReceiptTotalMatches() {
        long unrelated = id("INSERT INTO raw_materials(name,unit,supplier_id) SELECT 'DEMO unrelated','KG',supplier_id FROM suppliers WHERE name='DEMO 신속 공급' RETURNING raw_material_id");
        splitFlourLot(unrelated);
        assertThatThrownBy(this::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RAW_LOT_IDENTITY_MISMATCH");
    }

    private void splitFlourLot(Long materialOverride) {
        long material = materialOverride != null ? materialOverride : id("SELECT raw_material_id FROM raw_materials WHERE name='DEMO 밀가루'");
        jdbc.sql("UPDATE raw_material_lots SET quantity=quantity-1,remaining_quantity=remaining_quantity-1 WHERE lot_number='DEMO-RM-FLOUR'").update();
        jdbc.sql("""
                INSERT INTO raw_material_lots(raw_material_id,inbound_id,lot_number,quantity,remaining_quantity,production_date,expiry_date)
                SELECT :material,inbound_id,'DEMO-RM-FLOUR-SPLIT',1,1,production_date,expiry_date
                FROM raw_material_lots WHERE lot_number='DEMO-RM-FLOUR'
                """).param("material", material).update();
    }
}
