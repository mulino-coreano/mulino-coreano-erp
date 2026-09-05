package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InterfaceQueryIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void askInventoryUsesExplicitProductOrSkuSearchTerm() throws Exception {
        long warehouseId = warehouse("Query warehouse");
        String marker = shortId();
        stock("Amaretti " + marker, "AMR-" + marker, warehouseId, 125);
        stock("Biscotti " + marker, "BSC-" + marker, warehouseId, 80);

        mockMvc.perform(get("/api/v1/ask").param("q", "AMR-" + marker)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("AMR-" + marker))
                .andExpect(jsonPath("$.totalLocationCount").value(1))
                .andExpect(jsonPath("$.returnedLocationCount").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.inventory", hasSize(1)))
                .andExpect(jsonPath("$.inventory[0].sku").value("AMR-" + marker));
    }

    @Test
    void askInventoryWithoutSearchTermIsAnExplicitBoundedAllInventoryQuery() throws Exception {
        long warehouseId = warehouse("All inventory warehouse");
        String marker = shortId();
        stock("All inventory product " + marker, "ALL-" + marker, warehouseId, 42);

        mockMvc.perform(get("/api/v1/ask").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.returnedLocationCount").isNumber())
                .andExpect(jsonPath("$.totalLocationCount").isNumber())
                .andExpect(jsonPath("$.truncated").isBoolean())
                .andExpect(jsonPath("$.provenance").value(
                        "sources=stock,products,warehouses;generated_by=inventory_search"));
    }

    @Test
    void askInventoryReportsTotalReturnedAndTruncationTruthfully() throws Exception {
        long warehouseId = warehouse("Truncation warehouse");
        String marker = "Bounded-" + shortId();
        for (int index = 0; index < 21; index++) {
            stock(marker + " product " + index, "BD-" + shortId(), warehouseId, index + 1);
        }

        mockMvc.perform(get("/api/v1/ask").param("q", marker)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLocationCount").value(21))
                .andExpect(jsonPath("$.returnedLocationCount").value(20))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.inventory", hasSize(20)))
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("21"),
                                org.hamcrest.Matchers.containsString("20"))));
    }

    private long warehouse(String name) {
        return jdbc.sql("""
                        INSERT INTO warehouses (name, location, type)
                        VALUES (:name, 'Seoul', 'AMBIENT') RETURNING warehouse_id
                        """)
                .param("name", name + " " + shortId())
                .query(Long.class)
                .single();
    }

    private void stock(String name, String sku, long warehouseId, int quantity) {
        long productId = jdbc.sql("""
                        INSERT INTO products (name, sku, unit, expiry_days)
                        VALUES (:name, :sku, 'CASE', 180) RETURNING product_id
                        """)
                .param("name", name)
                .param("sku", sku)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO stock (product_id, warehouse_id, quantity)
                        VALUES (:productId, :warehouseId, :quantity)
                        """)
                .param("productId", productId)
                .param("warehouseId", warehouseId)
                .param("quantity", quantity)
                .update();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
