package com.mulinocoreano.backend.common.exception;

import com.mulinocoreano.backend.interfacepackage.ActiveRunConflictException;
import com.mulinocoreano.backend.interfacepackage.EventIdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StatusProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void responseStatusExceptionKeepsItsHttpStatus() throws Exception {
        mockMvc.perform(get("/probe/unavailable").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("CMN503"))
                .andExpect(jsonPath("$.message").value("No active ORCHESTRATOR agent is configured"));
    }

    @Test
    void annotatedConflictExceptionsStayConflicts() throws Exception {
        mockMvc.perform(get("/probe/active-run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CMN009"));

        mockMvc.perform(get("/probe/idempotency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CMN009"));
    }

    @Test
    void undeclaredFailuresStayInternal() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CMN500"));
    }

    @RestController
    static class StatusProbeController {

        @GetMapping("/probe/unavailable")
        void unavailable() {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "No active ORCHESTRATOR agent is configured");
        }

        @GetMapping("/probe/active-run")
        void activeRun() {
            throw new ActiveRunConflictException("wi-1");
        }

        @GetMapping("/probe/idempotency")
        void idempotency() {
            throw new EventIdempotencyConflictException("SUPPLIER_EMAIL_RECEIVED", "msg-1");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }
    }
}
