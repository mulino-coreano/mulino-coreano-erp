package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationBoundaryIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousRequestsCannotReadEvenHealth() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isUnauthorized());
    }
}
