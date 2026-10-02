package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.security.AgentActor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/agent/work-items")
public class AgentWorkController {
    private final AgentWorkService work;
    public AgentWorkController(AgentWorkService work) { this.work=work; }
    @PostMapping public JsonNode create(@RequestHeader("Authorization") String authorization,
                                        @RequestHeader("Idempotency-Key") String key,@RequestBody AgentWorkService.CreateWork request) {
        return work.create(authorization.substring(7),key,request);
    }
    @PostMapping("/{ref}/transition") public JsonNode transition(@PathVariable String ref,@RequestHeader("Authorization") String authorization,
                         @RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal AgentActor actor,@RequestBody AgentWorkService.Transition request) {
        return work.transition(authorization.substring(7),key,actor.agentKey(),actor.caseRef(),ref,request);
    }
}
