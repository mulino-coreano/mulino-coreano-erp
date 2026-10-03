package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="mulino.local-auth.service-secret=local-test-secret")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@org.springframework.transaction.annotation.Transactional
class WorkerBoundaryIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void missingWrongOrMixedIdentityCannotClaim() throws Exception {
        for(String secret:new String[]{"","wrong"}) {
            mvc.perform(post("/api/v1/internal/runs/claim").header("X-Mulino-Local-Service",secret)
                    .contentType("application/json").content("{\"workerId\":\"boundary\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/internal/runs/claim").header("X-Mulino-Local-Service","local-test-secret")
                .header("X-Mulino-Local-Role","MANAGER").contentType("application/json")
                .content("{\"workerId\":\"boundary\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/plans/missing/purchase-proposal").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/cases/missing/plans").header("X-Mulino-Local-Role","VIEWER")
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
}
