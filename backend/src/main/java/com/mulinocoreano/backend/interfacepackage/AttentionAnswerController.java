package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.security.ErpActor;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/attention")
public class AttentionAnswerController {
    private final AttentionAnswerService service;

    public AttentionAnswerController(AttentionAnswerService service) {
        this.service = service;
    }

    @PostMapping("/{id}/answer")
    public JsonNode answer(
            @PathVariable long id,
            @Valid @RequestBody AttentionAnswerRequest request,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal ErpActor actor) {
        return service.answer(id, request, actor, key);
    }
}
