package com.mulinocoreano.backend.planning;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PlanningSchemaIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    long product;
    long child;
    long supplier;
    long material;
    long warehouse;
    long user;

    @BeforeEach
    void masters() {
        product = id("INSERT INTO products(name,sku,unit,expiry_days) VALUES ('schema parent','SCHEMA-PARENT','EA',100) RETURNING product_id");
        child = id("INSERT INTO products(name,sku,unit,expiry_days,product_type) VALUES ('schema child','SCHEMA-CHILD','KG',100,'SEMI_FINISHED') RETURNING product_id");
        supplier = id("INSERT INTO suppliers(name,country) VALUES ('schema supplier','KR') RETURNING supplier_id");
        material = id("INSERT INTO raw_materials(name,unit,supplier_id) VALUES ('schema flour','KG'," + supplier + ") RETURNING raw_material_id");
        warehouse = id("INSERT INTO warehouses(name,type) VALUES ('schema plant','AMBIENT') RETURNING warehouse_id");
        user = id("INSERT INTO users(name,email,role) VALUES ('schema user','schema@example.invalid','OPERATOR') RETURNING user_id");
    }

    @Test
    void storesFractionalInventoryWithoutLosingExistingPositiveChecks() throws Exception {
        jdbc.sql("INSERT INTO stock(product_id,warehouse_id,quantity) VALUES (?,?,0.123456)").params(product, warehouse).update();
        assertThat(jdbc.sql("SELECT quantity FROM stock WHERE product_id=?").param(product).query(BigDecimal.class).single())
                .isEqualByComparingTo("0.123456");
        rejects("UPDATE stock SET quantity=-0.000001 WHERE product_id=" + product);
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('purchase_order_items','inbound','raw_material_lots','production_lots','production_ingredients','stock','order_items','outbound','outbound_lots') AND column_name IN ('quantity','remaining_quantity','received_quantity','quantity_used','lot_quantity','unit_price') AND numeric_precision=18 AND numeric_scale=6")
                .query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT numeric_precision=24 AND numeric_scale=9 FROM information_schema.columns WHERE table_schema='public' AND table_name='purchase_order_items' AND column_name='unit_price'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void rejectsNonFiniteQuantitiesIntroducedByTheNumericType() throws Exception {
        rejects("INSERT INTO stock(product_id,warehouse_id,quantity) VALUES (" + product + "," + warehouse + ",'NaN')");
        rejects("INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from) VALUES (" + product + ",1,'NaN',1,'2026-01-01')");
        jdbc.sql(termsSql("KG", "1")).update();
        rejects("UPDATE supplier_material_terms SET unit_price='NaN' WHERE raw_material_id=" + material);
    }

    @Test
    void rejectsOverlappingInclusiveActiveBomVersionsAndAllowsInactiveDrafts() throws Exception {
        bom(product, 1, "2026-01-01", "2026-09-05");
        rejects(bomSql(product, 2, "2026-09-05", "2026-12-31"));
        bom(product, 3, "2026-09-06", null);
        jdbc.sql("INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from,is_active) VALUES (?,4,10,1,'2026-01-01',false)").param(product).update();
    }

    @Test
    void rejectsComponentCyclesAndExclusiveTargetViolations() throws Exception {
        long parentBom = bom(product, 1, "2026-01-01", null);
        long childBom = bom(child, 1, "2026-01-01", null);
        jdbc.sql("INSERT INTO bom_components(bom_version_id,child_product_id,quantity_per_batch) VALUES (?,?,0.5)").params(parentBom, child).update();
        rejects("INSERT INTO bom_components(bom_version_id,child_product_id,quantity_per_batch) VALUES (" + childBom + "," + product + ",1)");
        rejects("INSERT INTO bom_components(bom_version_id,child_product_id,raw_material_id,quantity_per_batch) VALUES (" + childBom + "," + product + "," + material + ",1)");
        rejects("INSERT INTO bom_components(bom_version_id,raw_material_id,quantity_per_batch) VALUES (" + childBom + "," + material + ",0)");
    }

    @Test
    void validatesPurchaseUnitDimensionsAndExplicitConversion() throws Exception {
        jdbc.sql(termsSql("G", "0.001")).update();
        rejects(termsSql("L", "1"));
        rejects(termsSql("KG", "1000"));
        rejects(termsSql("KG", "0"));
        assertThat(jdbc.sql("SELECT dimension FROM measurement_units WHERE code='CASE'").query(String.class).single())
                .isNotEqualTo(jdbc.sql("SELECT dimension FROM measurement_units WHERE code='EA'").query(String.class).single());
    }

    @Test
    void preventsSelfLotInputAndKnownCrossWarehouseConsumption() throws Exception {
        long parentLot = lot(product, warehouse, "P");
        long sourceLot = lot(child, warehouse, "C");
        long record = id("INSERT INTO production_records(lot_id,warehouse_id,operator_id,process_type,start_time) VALUES (" + parentLot + "," + warehouse + "," + user + ",'MIX',CURRENT_TIMESTAMP) RETURNING production_record_id");
        jdbc.sql("INSERT INTO production_product_inputs(production_record_id,source_production_lot_id,quantity_used) VALUES (?,?,0.125)").params(record, sourceLot).update();
        rejects("INSERT INTO production_product_inputs(production_record_id,source_production_lot_id,quantity_used) VALUES (" + record + "," + parentLot + ",1)");
        long otherWarehouse = id("INSERT INTO warehouses(name,type) VALUES ('other','AMBIENT') RETURNING warehouse_id");
        rejects("UPDATE production_lots SET warehouse_id=" + otherWarehouse + " WHERE production_lot_id=" + sourceLot);
        rejects("UPDATE production_records SET lot_id=" + sourceLot + " WHERE production_record_id=" + record);
    }

    @Test
    void preservesPlanVersionsAndEnforcesExplicitActivePlanningCase() throws Exception {
        long first = planningCase("SCHEMA-C1");
        long second = planningCase("SCHEMA-C2");
        jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) VALUES (?,?)").params(first,warehouse).update();
        rejects("INSERT INTO planning_cases(case_id,warehouse_id) VALUES (" + second + "," + warehouse + ")");
        long plan = id("INSERT INTO replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash) VALUES ('SCHEMA-PLAN'," + first + "," + warehouse + ",1,'2026-09-05T00:00:00+09:00',30,'2026-10-04','{}','{}',repeat('a',64),repeat('b',64)) RETURNING replenishment_plan_id");
        rejects("UPDATE replenishment_plans SET result='{\"changed\":true}' WHERE replenishment_plan_id=" + plan);
        rejects("DELETE FROM replenishment_plans WHERE replenishment_plan_id=" + plan);
        jdbc.sql("UPDATE planning_cases SET status='CLOSED',closed_at=CURRENT_TIMESTAMP WHERE case_id=?").param(first).update();
        jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) VALUES (?,?)").params(second,warehouse).update();
    }

    private long planningCase(String ref) {
        return id("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES ('" + ref + "','schema','schema','ACT') RETURNING case_id");
    }

    private long lot(long productId, long warehouseId, String suffix) {
        return id("INSERT INTO production_lots(product_id,warehouse_id,lot_number,production_date,expiry_date,quantity) VALUES (" + productId + "," + warehouseId + ",'SCHEMA-" + suffix + "','2026-09-01','2026-12-01',10) RETURNING production_lot_id");
    }

    private String termsSql(String unit, String factor) {
        return "INSERT INTO supplier_material_terms(raw_material_id,supplier_id,purchase_unit,base_quantity_per_purchase_unit,unit_price,minimum_order_quantity,order_multiple,lead_time_days,valid_from) VALUES (" + material + "," + supplier + ",'" + unit + "'," + factor + ",1,1,1,1,'2026-01-01')";
    }

    private long bom(long productId, int version, String start, String end) {
        return id(bomSql(productId, version, start, end) + " RETURNING bom_version_id");
    }

    private String bomSql(long productId, int version, String start, String end) {
        return "INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from,valid_to) VALUES (" + productId + "," + version + ",10,1,'" + start + "'," + (end == null ? "NULL" : "'" + end + "'") + ")";
    }

    private long id(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    private void rejects(String sql) throws Exception {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        connection.rollback(before);
    }
}
