package com.mulinocoreano.backend.procurement;

import com.mulinocoreano.backend.interfacepackage.InvalidInterfaceRequestException;
import com.mulinocoreano.backend.security.*;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PurchaseController {
    private final PurchaseQueries queries;
    private final PurchaseProposalService proposals;
    private final PurchaseDecisionService decisions;

    public PurchaseController(
            PurchaseQueries queries,
            PurchaseProposalService proposals,
            PurchaseDecisionService decisions) {
        this.queries = queries;
        this.proposals = proposals;
        this.decisions = decisions;
    }

    @GetMapping("/approvals/{id}")
    public JsonNode approval(@PathVariable long id) {
        return queries.approval(id);
    }

    @GetMapping("/purchase-orders/{id}")
    public JsonNode order(@PathVariable long id) {
        return queries.order(id);
    }

    @PostMapping("/plans/{ref}/purchase-proposal")
    public ResponseEntity<JsonNode> propose(
            @PathVariable String ref,
            @RequestHeader("Authorization") String auth,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal AgentActor actor,
            @RequestBody Map<String, Object> body) {
        if (!body.isEmpty())
            throw new InvalidInterfaceRequestException(
                    "The server selects purchase lines from the plan; the request body must be"
                            + " empty");
        return response(proposals.propose(ref, auth.substring(7), actor, key));
    }

    @PostMapping("/approvals/{id}/decision")
    public ResponseEntity<JsonNode> decide(
            @PathVariable long id,
            @Valid @RequestBody PurchaseDecisionRequest request,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal ErpActor actor) {
        return response(decisions.decide(id, request, actor, key));
    }

    private ResponseEntity<JsonNode> response(JsonNode body) {
        return ResponseEntity.status(body.has("error") ? 409 : 200).body(body);
    }

    @ExceptionHandler(InvalidInterfaceRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidInterfaceRequestException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PURCHASE_REQUEST"));
    }
}
