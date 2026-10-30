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
    void workItemsIdentifyHumanAgentAndUnassignedResponsibility() throws Exception {
        String ref = "CASE-" + shortId();
        long caseId = jdbc.sql("INSERT INTO cases(case_ref,title,objective,intent_type) VALUES(:ref,'Owners','Owners','ACT') RETURNING case_id")
                .param("ref", ref).query(Long.class).single();
        long userId = jdbc.sql("INSERT INTO users(name,email,password,role) VALUES('Human owner',:email,'test-only','OPERATOR') RETURNING user_id")
                .param("email", shortId()+"@owners.invalid").query(Long.class).single();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_user_id) VALUES(:ref,:caseId,'Human',:userId)")
                .param("ref", "WI-"+shortId()).param("caseId", caseId).param("userId", userId).update();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title,assigned_agent_id) SELECT :ref,:caseId,'Agent',agent_id FROM agents WHERE agent_key='ORCHESTRATOR'")
                .param("ref", "WI-"+shortId()).param("caseId", caseId).update();
        jdbc.sql("INSERT INTO work_items(work_item_ref,case_id,title) VALUES(:ref,:caseId,'Unassigned')")
                .param("ref", "WI-"+shortId()).param("caseId", caseId).update();
        mockMvc.perform(get("/api/v1/cases/{caseRef}/work-items", ref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assigneeType").value("USER"))
                .andExpect(jsonPath("$[0].assigneeId").value(userId))
                .andExpect(jsonPath("$[0].assigneeName").value("Human owner"))
                .andExpect(jsonPath("$[0].assignedAgent").doesNotExist())
                .andExpect(jsonPath("$[1].assigneeType").value("AGENT"))
                .andExpect(jsonPath("$[1].assigneeId").isNumber())
                .andExpect(jsonPath("$[1].assignedAgent").isString())
                .andExpect(jsonPath("$[2].assigneeType").value("UNASSIGNED"))
                .andExpect(jsonPath("$[2].assigneeId").doesNotExist());
    }

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
