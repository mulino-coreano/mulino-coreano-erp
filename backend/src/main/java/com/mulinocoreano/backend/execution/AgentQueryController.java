package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.planning.PlanDto;
import com.mulinocoreano.backend.security.AgentActor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentQueryController {
    private final AgentQueryService queries;

    public AgentQueryController(AgentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/cases/{ref}")
    public Map<String, Object> caseContext(
            @PathVariable String ref,
            @RequestHeader("Authorization") String auth,
            @AuthenticationPrincipal AgentActor actor) {
        return queries.caseContext(auth.substring(7), actor.agentKey(), ref);
    }

    @GetMapping("/plans/{ref}")
    public PlanDto plan(
            @PathVariable String ref,
            @RequestHeader("Authorization") String auth,
            @AuthenticationPrincipal AgentActor actor) {
        return queries.plan(auth.substring(7), actor.agentKey(), actor.caseRef(), ref);
    }

    @GetMapping("/materials/{id}")
    public Map<String, Object> material(
            @PathVariable long id,
            @RequestHeader("Authorization") String auth,
            @AuthenticationPrincipal AgentActor actor) {
        return queries.material(auth.substring(7), actor.agentKey(), actor.caseRef(), id);
    }

    @GetMapping("/purchase-orders/{id}")
    public tools.jackson.databind.JsonNode purchaseOrder(
            @PathVariable long id,
            @RequestHeader("Authorization") String auth,
            @AuthenticationPrincipal AgentActor actor) {
        return queries.purchaseOrder(auth.substring(7), actor.agentKey(), actor.caseRef(), id);
    }
}
