package com.mulinocoreano.backend.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.security.HumanActor;
import com.mulinocoreano.backend.security.WithTestActor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithTestActor(
        role = "VIEWER",
        capabilities = {"erp:read"})
class PurchaseReadIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired CanonicalJson json;

    @Test
    void evidenceProjectionPreservesExactDecimalsAndExplicitlyMissingFields() {
        var document = json.readTree("""
                {"planEvidence":{"sourceSnapshot":{"supply":[
                  {"quantity":123456789012345.123456789,"projected":false},
                  {"quantity":0.000001,"projected":true}]},
                  "result":{"totalAmount":123456789012345.123456789}}}
                """);
        var evidence = ApprovalEvidenceProjection.project(document).path("planEvidence");
        assertThat(evidence.path("supply").get(0).path("quantity").decimalValue())
                .isEqualByComparingTo("123456789012345.123456789");
        assertThat(evidence.path("supply").get(1).path("quantity").decimalValue())
                .isEqualByComparingTo("0.000001");
        assertThat(evidence.path("result").path("totalAmount").decimalValue())
                .isEqualByComparingTo("123456789012345.123456789");
        assertThat(evidence.path("sourceRefs").isNull()).isTrue();
        assertThat(evidence.path("boms").isNull()).isTrue();
        assertThat(evidence.has("sourceSnapshot")).isFalse();
    }

    @Test
    void employeeCanReadExistingPurchaseWithoutInventingAnApprovalOrBuyUnit() throws Exception {
        var actor =
                (HumanActor) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        long supplier =
                jdbc.sql(
                                "INSERT INTO suppliers(name,country) VALUES('구매 조회 시험','KR')"
                                        + " RETURNING supplier_id")
                        .query(Long.class)
                        .single();
        long material =
                jdbc.sql(
                                "INSERT INTO raw_materials(name,unit,supplier_id) VALUES('조회용"
                                        + " 원재료','KG',:supplier) RETURNING raw_material_id")
                        .param("supplier", supplier)
                        .query(Long.class)
                        .single();
        long order =
                jdbc.sql(
                                "INSERT INTO"
                                    + " purchase_orders(purchase_order_id,supplier_id,created_by,order_date,status)"
                                    + " VALUES(9007199254740993,:supplier,:user,DATE"
                                    + " '2026-09-05','ORDERED') RETURNING purchase_order_id")
                        .param("supplier", supplier)
                        .param("user", actor.userId())
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "INSERT INTO"
                            + " purchase_order_items(purchase_order_id,raw_material_id,quantity,unit_price)"
                            + " VALUES(:po,:material,12.345678,123456789012345.123456789)")
                .param("po", order)
                .param("material", material)
                .update();
        var response =
                mvc.perform(get("/api/v1/purchase-orders/{id}", order))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.id").value(order))
                        .andExpect(jsonPath("$.supplierName").value("구매 조회 시험"))
                        .andExpect(jsonPath("$.items[0].materialId").value(material))
                        .andExpect(jsonPath("$.items[0].baseUnit").value("KG"))
                        .andExpect(jsonPath("$.items[0].baseQuantity").value(12.345678))
                        .andExpect(jsonPath("$.items[0].buyQuantity").doesNotExist())
                        .andExpect(jsonPath("$.approvalId").doesNotExist())
                        .andExpect(jsonPath("$.taxInvoiceNumber").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var document = json.readTree(response);
        assertThat(document.path("id").asLong()).isEqualTo(9007199254740993L);
        assertThat(document.path("items").get(0).path("baseUnitPrice").decimalValue())
                .isEqualByComparingTo("123456789012345.123456789");
    }
}
