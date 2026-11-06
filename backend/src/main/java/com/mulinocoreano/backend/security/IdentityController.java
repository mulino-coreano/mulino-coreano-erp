package com.mulinocoreano.backend.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class IdentityController {
    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@AuthenticationPrincipal ErpActor actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issuer", actor.issuer());
        result.put("subject", actor.subject());
        result.put("capabilities", actor.capabilities().stream().sorted().toList());
        if (actor instanceof HumanActor human) {
            result.put("actorType", "HUMAN");
            result.put("userId", human.userId());
            result.put("name", human.name());
            result.put("role", human.role());
        } else if (actor instanceof ServiceActor service) {
            result.put("actorType", "SERVICE");
            result.put("clientId", service.clientId());
        }
        return result;
    }
}
