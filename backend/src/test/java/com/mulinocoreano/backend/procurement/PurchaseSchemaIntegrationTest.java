package com.mulinocoreano.backend.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Savepoint;
import java.util.List;

import javax.sql.DataSource;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=purchase_schema_it",
            "spring.datasource.hikari.schema=purchase_schema_it",
            "spring.flyway.clean-disabled=false"
        })
@Transactional
class PurchaseSchemaIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    long manager,
            requester,
            supplier,
            otherSupplier,
            material,
            otherMaterial,
            warehouse,
            otherWarehouse;
    long caseId, otherCase, work, otherWork, plan, otherPlan, agent, term;

    @BeforeEach
    void fixture() {
        manager =
                id(
                        "INSERT INTO users(name,email,role) VALUES('purchase"
                            + " manager','purchase-manager@example.invalid','MANAGER') RETURNING"
                            + " user_id");
        requester =
                id(
                        "INSERT INTO users(name,email,role) VALUES('purchase"
                            + " requester','purchase-requester@example.invalid','OPERATOR')"
                            + " RETURNING user_id");
        supplier =
                id(
                        "INSERT INTO suppliers(name,country) VALUES('purchase supplier','KR')"
                            + " RETURNING supplier_id");
        otherSupplier =
                id(
                        "INSERT INTO suppliers(name,country) VALUES('other purchase supplier','KR')"
                            + " RETURNING supplier_id");
        material =
                id(
                        "INSERT INTO raw_materials(name,unit,supplier_id) VALUES('purchase"
                            + " flour','G',"
                                + supplier
                                + ") RETURNING raw_material_id");
        otherMaterial =
                id(
                        "INSERT INTO raw_materials(name,unit,supplier_id) VALUES('other flour','G',"
                                + supplier
                                + ") RETURNING raw_material_id");
        warehouse =
                id(
                        "INSERT INTO warehouses(name,type) VALUES('purchase plant','AMBIENT')"
                            + " RETURNING warehouse_id");
        otherWarehouse =
                id(
                        "INSERT INTO warehouses(name,type) VALUES('other purchase plant','AMBIENT')"
                            + " RETURNING warehouse_id");
        caseId = newCase("CASE-PUR-SCHEMA", warehouse);
        otherCase = newCase("CASE-PUR-OTHER", otherWarehouse);
        agent = id("SELECT agent_id FROM agents WHERE agent_key='PROCUREMENT'");
        work =
                id(
                        "INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id)"
                            + " VALUES('WI-PUR-SCHEMA',"
                                + caseId
                                + ",'proposal',"
                                + agent
                                + ") RETURNING work_item_id");
        otherWork =
                id(
                        "INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id)"
                            + " VALUES('WI-PUR-OTHER',"
                                + otherCase
                                + ",'other',"
                                + agent
                                + ") RETURNING work_item_id");
        plan = newPlan("PLAN-PUR-SCHEMA", caseId, warehouse, 1);
        otherPlan = newPlan("PLAN-PUR-OTHER", otherCase, otherWarehouse, 1);
        term =
                id(
                        "INSERT INTO"
                            + " supplier_material_terms(raw_material_id,supplier_id,purchase_unit,base_quantity_per_purchase_unit,unit_price,minimum_order_quantity,order_multiple,lead_time_days,valid_from)"
                            + " VALUES("
                                + material
                                + ","
                                + supplier
                                + ",'KG',1000,1.234567,0,0.5,1,'2026-01-01') RETURNING"
                                + " supplier_material_term_id");
    }

    @Test
    void linkedProposalRequiresCompleteScopeAndOneProposalPerPlan() throws Exception {
        long action = action();
        assertThat(
                        jdbc.sql(
                                        "SELECT proposal_version FROM governance_actions WHERE"
                                            + " governance_action_id=?")
                                .param(action)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        rejects(actionSql(caseId, work, plan, 1));
        rejects(actionSql(otherCase, work, otherPlan, 1));
        rejects(actionSql(caseId, work, otherPlan, 1));
        rejects(actionSql(otherCase, otherWork, otherPlan, 2));
        rejects(actionSql(otherCase, otherWork, otherPlan, 1).replace("'MANAGER'", "'ADMIN'"));
        rejects(
                actionSql(otherCase, otherWork, otherPlan, 1)
                        .replace("'REPLENISHMENT_PLAN'", "'PURCHASE_ORDER'"));
        rejects(actionSql(otherCase, otherWork, otherPlan, 1).replace("repeat('b',64)", "NULL"));
    }

    @Test
    void proposalIdentityPayloadAndTerminalDecisionCannotBeRewritten() throws Exception {
        long action = action();
        rejects(
                "UPDATE governance_actions SET payload='{\"changed\":true}' WHERE"
                    + " governance_action_id="
                        + action);
        rejects(
                "UPDATE governance_actions SET requested_by="
                        + manager
                        + " WHERE governance_action_id="
                        + action);
        rejects("DELETE FROM governance_actions WHERE governance_action_id=" + action);
        jdbc.sql("UPDATE governance_actions SET status='APPROVED' WHERE governance_action_id=?")
                .param(action)
                .update();
        rejects(
                "UPDATE governance_actions SET status='BLOCKED' WHERE governance_action_id="
                        + action);
        rejects(
                "UPDATE governance_actions SET status='PENDING' WHERE governance_action_id="
                        + action);
        rejects("TRUNCATE governance_actions CASCADE");
    }

    @Test
    void linkedDecisionsMustBeFinalUniqueAndAppendOnly() throws Exception {
        long action = action();
        rejects(
                "INSERT INTO governance_decisions(governance_action_id,decided_by,decision,reason)"
                    + " VALUES("
                        + action
                        + ","
                        + manager
                        + ",'APPROVE','missing final flag')");
        long decision = decision(action);
        rejects(
                "INSERT INTO"
                    + " governance_decisions(governance_action_id,decided_by,decision,reason,is_final)"
                    + " VALUES("
                        + action
                        + ","
                        + requester
                        + ",'BLOCK','duplicate',true)");
        rejects(
                "UPDATE governance_decisions SET reason='rewritten' WHERE governance_decision_id="
                        + decision);
        rejects(
                "UPDATE governance_decisions SET is_final=false WHERE governance_decision_id="
                        + decision);
        rejects("DELETE FROM governance_decisions WHERE governance_decision_id=" + decision);
        rejects("TRUNCATE governance_decisions CASCADE");
    }

    @Test
    void approvalAttentionAndProcurementMarkersStayInsideTheCase() throws Exception {
        long action = action();
        long attention =
                id(
                        "INSERT INTO"
                            + " attention_requests(case_id,work_item_id,reason_type,title,question,governance_action_id)"
                            + " VALUES("
                                + caseId
                                + ","
                                + work
                                + ",'AUTHORITY_REQUIRED','approval','approve?',"
                                + action
                                + ") RETURNING attention_request_id");
        assertThat(
                        jdbc.sql(
                                        "SELECT version FROM attention_requests WHERE"
                                            + " attention_request_id=?")
                                .param(attention)
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        rejects("UPDATE attention_requests SET version=0 WHERE attention_request_id=" + attention);
        rejects(
                "UPDATE attention_requests SET case_id="
                        + otherCase
                        + ",work_item_id="
                        + otherWork
                        + " WHERE attention_request_id="
                        + attention);
        jdbc.sql(
                        "UPDATE work_items SET procurement_plan_id=?,procurement_outcome='PROPOSED'"
                            + " WHERE work_item_id=?")
                .params(plan, work)
                .update();
        rejects(
                "UPDATE work_items SET procurement_plan_id="
                        + otherPlan
                        + " WHERE work_item_id="
                        + work);
        rejects("UPDATE work_items SET procurement_outcome=NULL WHERE work_item_id=" + work);
        rejects("UPDATE work_items SET procurement_outcome='MADE_UP' WHERE work_item_id=" + work);
    }

    @Test
    void applicationMayBeInsertedAfterItsOrdersAndPreservesExactBuyAndBasePrices()
            throws Exception {
        long action = action();
        long decision = decision(action);
        jdbc.sql("UPDATE governance_actions SET status='APPROVED' WHERE governance_action_id=?")
                .param(action)
                .update();
        long application =
                id(
                        "SELECT"
                            + " nextval(pg_get_serial_sequence('purchase_applications','purchase_application_id'))");
        long po = order(application);
        long item = id(itemSql(po) + " RETURNING purchase_order_item_id");
        jdbc.sql(applicationSql(application, action, decision, plan, caseId, work)).update();
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        assertThat(
                        jdbc.sql(
                                        "SELECT unit_price FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("0.001234567");
        assertThat(
                        jdbc.sql(
                                        "SELECT purchase_unit_price FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("1.234567");
        assertThat(
                        jdbc.sql(
                                        "SELECT quantity FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("1500");
        assertThat(
                        jdbc.sql(
                                        "SELECT line_amount FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("2");
    }

    @Test
    void missingDeferredApplicationFailsAtConstraintCheck() throws Exception {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint before = connection.setSavepoint();
        order(999999999L);
        assertThatThrownBy(() -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update())
                .isInstanceOf(DataAccessException.class);
        connection.rollback(before);
    }

    @Test
    void purchaseDetailRejectsMismatchedMathUnitsSupplierAndPartialContract() throws Exception {
        long po = order(null);
        long item = id(itemSql(po) + " RETURNING purchase_order_item_id");
        for (String update :
                List.of(
                        "quantity=1499",
                        "unit_price=0.001234568",
                        "line_amount=1",
                        "purchase_quantity=NULL",
                        "base_unit='KG'",
                        "purchase_unit='L'",
                        "purchase_unit_price='NaN'",
                        "base_quantity_per_purchase_unit=0",
                        "line_amount='NaN'",
                        "raw_material_id=" + otherMaterial)) {
            rejects(
                    "UPDATE purchase_order_items SET "
                            + update
                            + " WHERE purchase_order_item_id="
                            + item);
        }
        rejects(
                "UPDATE purchase_order_items SET"
                    + " base_unit='KG',base_quantity_per_purchase_unit=1,quantity=1.5,unit_price=1.234567"
                    + " WHERE purchase_order_item_id="
                        + item);
        long wrongSupplierOrder =
                id(
                        "INSERT INTO purchase_orders(supplier_id,created_by,order_date) VALUES("
                                + otherSupplier
                                + ","
                                + manager
                                + ",'2026-09-05') RETURNING purchase_order_id");
        rejects(itemSql(wrongSupplierOrder));
        rejects(
                "UPDATE purchase_orders SET supplier_id="
                        + otherSupplier
                        + " WHERE purchase_order_id="
                        + po);
    }

    @Test
    void applicationBoundOrdersRequireTheFullNewDetailContract() throws Exception {
        long po = order(999999999L);
        rejects(
                "INSERT INTO"
                    + " purchase_order_items(purchase_order_id,raw_material_id,quantity,unit_price)"
                    + " VALUES("
                        + po
                        + ","
                        + material
                        + ",1,1)");
    }

    @Test
    void orderApplicationScopeIsCheckedAfterDeferredApplicationCreation() throws Exception {
        long action = action();
        long decision = decision(action);
        jdbc.sql("UPDATE governance_actions SET status='APPROVED' WHERE governance_action_id=?")
                .param(action)
                .update();
        long application =
                id(
                        "SELECT"
                            + " nextval(pg_get_serial_sequence('purchase_applications','purchase_application_id'))");
        jdbc.sql(applicationSql(application, action, decision, plan, caseId, work)).update();
        long po = order(application);
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        rejects(
                "UPDATE purchase_orders SET warehouse_id="
                        + otherWarehouse
                        + " WHERE purchase_order_id="
                        + po);
        rejects(
                "UPDATE purchase_orders SET created_by="
                        + requester
                        + " WHERE purchase_order_id="
                        + po);
    }

    @Test
    void applicationIsUniqueImmutableAndBoundToItsApprovedDecisionAndCase() throws Exception {
        long action = action();
        long decision = decision(action);
        jdbc.sql("UPDATE governance_actions SET status='APPROVED' WHERE governance_action_id=?")
                .param(action)
                .update();
        long application =
                id(
                        "SELECT"
                            + " nextval(pg_get_serial_sequence('purchase_applications','purchase_application_id'))");
        jdbc.sql(applicationSql(application, action, decision, plan, caseId, work)).update();
        rejects(applicationSql(application + 10000, action, decision, plan, caseId, work));
        rejects(
                "UPDATE purchase_applications SET verification_receipt='{\"changed\":true}' WHERE"
                    + " purchase_application_id="
                        + application);
        rejects("DELETE FROM purchase_applications WHERE purchase_application_id=" + application);
        rejects("TRUNCATE purchase_applications CASCADE");
        long secondAction =
                id(
                        actionSql(otherCase, otherWork, otherPlan, 1)
                                + " RETURNING governance_action_id");
        long secondDecision = decision(secondAction);
        jdbc.sql("UPDATE governance_actions SET status='APPROVED' WHERE governance_action_id=?")
                .param(secondAction)
                .update();
        rejects(
                applicationSql(
                        application + 10000, secondAction, secondDecision, plan, caseId, work));
        rejects(
                applicationSql(
                        application + 10000,
                        secondAction,
                        decision,
                        otherPlan,
                        otherCase,
                        otherWork));
        rejects(
                applicationSql(
                                application + 10000,
                                secondAction,
                                secondDecision,
                                otherPlan,
                                otherCase,
                                otherWork)
                        .replace("," + manager + ",'{}')", "," + requester + ",'{}')"));
    }

    @Test
    void auditHistoryCannotBeTruncatedEvenWhenEmpty() throws Exception {
        rejects("TRUNCATE governance_audit_logs");
    }

    @Test
    void everySnapshotSourceWriteAcquiresTheSharedGuard() {
        for (String table :
                List.of(
                        "warehouses",
                        "planning_policies",
                        "measurement_units",
                        "products",
                        "raw_materials",
                        "bom_versions",
                        "bom_components",
                        "outbound",
                        "outbound_lots",
                        "production_lots",
                        "production_records",
                        "production_product_inputs",
                        "stock",
                        "purchase_orders",
                        "purchase_order_items",
                        "inbound",
                        "raw_material_lots",
                        "production_ingredients",
                        "orders",
                        "order_items",
                        "supplier_material_terms",
                        "suppliers",
                        "supplier_certifications")) {
            long before = id("SELECT revision FROM planning_data_guard WHERE guard_id=1");
            jdbc.sql("DELETE FROM " + table + " WHERE false").update();
            assertThat(id("SELECT revision FROM planning_data_guard WHERE guard_id=1"))
                    .as(table)
                    .isGreaterThan(before);
            assertThat(
                            jdbc.sql(
                                            "SELECT EXISTS(SELECT 1 FROM pg_trigger WHERE"
                                                + " tgrelid=CAST(? AS regclass) AND NOT"
                                                + " tgisinternal AND (tgtype & 62)=62 AND (tgtype &"
                                                + " 1)=0)")
                                    .param(table)
                                    .query(Boolean.class)
                                    .single())
                    .as(table + " statement INSERT/UPDATE/DELETE/TRUNCATE")
                    .isTrue();
        }
        long before = id("SELECT revision FROM planning_data_guard WHERE guard_id=1");
        jdbc.sql("TRUNCATE stock").update();
        assertThat(id("SELECT revision FROM planning_data_guard WHERE guard_id=1"))
                .isGreaterThan(before);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void upgradingV22PreservesLegacyMultistepActionsDecisionsAndBasePrices() {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("purchase_schema_it");
        flyway.clean();
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("purchase_schema_it")
                .defaultSchema("purchase_schema_it")
                .target("22")
                .load()
                .migrate();
        long user =
                id(
                        "INSERT INTO users(name,email,role)"
                            + " VALUES('legacy','legacy-purchase@example.invalid','ADMIN')"
                            + " RETURNING user_id");
        long legacySupplier =
                id(
                        "INSERT INTO suppliers(name,country) VALUES('legacy supplier','KR')"
                            + " RETURNING supplier_id");
        long raw =
                id(
                        "INSERT INTO raw_materials(name,unit,supplier_id) VALUES('legacy raw','KG',"
                                + legacySupplier
                                + ") RETURNING raw_material_id");
        long po =
                id(
                        "INSERT INTO purchase_orders(supplier_id,created_by,order_date) VALUES("
                                + legacySupplier
                                + ","
                                + user
                                + ",'2026-01-01') RETURNING purchase_order_id");
        long item =
                id(
                        "INSERT INTO"
                            + " purchase_order_items(purchase_order_id,raw_material_id,quantity,unit_price)"
                            + " VALUES("
                                + po
                                + ","
                                + raw
                                + ",1.25,1.234567) RETURNING purchase_order_item_id");
        long action =
                id(
                        "INSERT INTO"
                            + " governance_actions(requested_by,action_type,resource_type,resource_id,payload,current_step,total_steps,required_role,status)"
                            + " VALUES("
                                + user
                                + ",'LEGACY','CASE',1,'{\"legacy\":true}',2,3,'ADMIN','APPROVED')"
                                + " RETURNING governance_action_id");
        jdbc.sql(
                        "INSERT INTO"
                            + " governance_decisions(governance_action_id,decided_by,decision,reason,decided_at)"
                            + " VALUES(?,?,'APPROVE','step 1','2026-01-01'),(?,?,'APPROVE','step"
                            + " 2','2026-01-02')")
                .params(action, user, action, user)
                .update();
        flyway.migrate();
        assertThat(
                        jdbc.sql(
                                        "SELECT unit_price FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("1.234567");
        assertThat(
                        jdbc.sql(
                                        "SELECT purchase_quantity IS NULL AND purchase_unit IS NULL"
                                            + " FROM purchase_order_items WHERE"
                                            + " purchase_order_item_id=?")
                                .param(item)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc.sql(
                                        "SELECT replenishment_plan_id IS NULL AND current_step=2"
                                            + " AND total_steps=3 AND status='APPROVED' FROM"
                                            + " governance_actions WHERE governance_action_id=?")
                                .param(action)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM governance_decisions WHERE"
                                            + " governance_action_id=? AND NOT is_final")
                                .param(action)
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
        jdbc.sql("UPDATE governance_actions SET current_step=3 WHERE governance_action_id=?")
                .param(action)
                .update();
    }

    private long newCase(String ref, long plant) {
        long result =
                id(
                        "INSERT INTO cases(case_ref,title,objective,intent_type,opened_by_user_id)"
                            + " VALUES('"
                                + ref
                                + "','purchase','purchase','ACT',"
                                + requester
                                + ") RETURNING case_id");
        jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) VALUES(?,?)")
                .params(result, plant)
                .update();
        return result;
    }

    private long newPlan(String ref, long cid, long plant, int version) {
        return id(
                "INSERT INTO"
                    + " replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash)"
                    + " VALUES('"
                        + ref
                        + "',"
                        + cid
                        + ","
                        + plant
                        + ","
                        + version
                        + ",'2026-09-05T00:00:00Z',30,'2026-10-04','{}','{\"status\":\"READY\"}',repeat('a',64),repeat('b',64))"
                        + " RETURNING replenishment_plan_id");
    }

    private String actionSql(long cid, long wi, long pid, int version) {
        return "INSERT INTO"
                   + " governance_actions(requested_by,action_type,resource_type,resource_id,payload,required_role,case_id,work_item_id,replenishment_plan_id,proposed_by_agent_id,proposal_version,proposal_hash)"
                   + " VALUES("
                + requester
                + ",'PURCHASE_PROPOSAL','REPLENISHMENT_PLAN',"
                + pid
                + ",'{}','MANAGER',"
                + cid
                + ","
                + wi
                + ","
                + pid
                + ","
                + agent
                + ","
                + version
                + ",repeat('b',64))";
    }

    private long action() {
        return id(actionSql(caseId, work, plan, 1) + " RETURNING governance_action_id");
    }

    private long decision(long action) {
        return id(
                "INSERT INTO"
                    + " governance_decisions(governance_action_id,decided_by,decision,reason,is_final)"
                    + " VALUES("
                        + action
                        + ","
                        + manager
                        + ",'APPROVE','approved',true) RETURNING governance_decision_id");
    }

    private long order(Long application) {
        return id(
                "INSERT INTO"
                    + " purchase_orders(supplier_id,created_by,order_date,status,purchase_application_id,warehouse_id,expected_delivery_date)"
                    + " VALUES("
                        + supplier
                        + ","
                        + manager
                        + ",'2026-09-05','ORDERED',"
                        + application
                        + ","
                        + warehouse
                        + ",'2026-09-10') RETURNING purchase_order_id");
    }

    private String itemSql(long po) {
        return "INSERT INTO"
                   + " purchase_order_items(purchase_order_id,raw_material_id,quantity,unit_price,purchase_quantity,purchase_unit_price,purchase_unit,base_unit,base_quantity_per_purchase_unit,line_amount,expected_delivery_date,supplier_material_term_id)"
                   + " VALUES("
                + po
                + ","
                + material
                + ",1500,0.001234567,1.5,1.234567,'KG','G',1000,2,'2026-09-10',"
                + term
                + ")";
    }

    private String applicationSql(
            long id, long action, long decision, long pid, long cid, long wi) {
        return "INSERT INTO"
                   + " purchase_applications(purchase_application_id,governance_action_id,governance_decision_id,replenishment_plan_id,case_id,work_item_id,applied_by_user_id,verification_receipt)"
                   + " VALUES("
                + id
                + ","
                + action
                + ","
                + decision
                + ","
                + pid
                + ","
                + cid
                + ","
                + wi
                + ","
                + manager
                + ",'{}')";
    }

    private long id(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void rejects(String sql) throws Exception {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint before = connection.setSavepoint();
        assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(DataAccessException.class);
        connection.rollback(before);
    }
}
