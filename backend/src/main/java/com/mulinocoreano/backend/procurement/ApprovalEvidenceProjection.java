package com.mulinocoreano.backend.procurement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Whitelisted evidence from the proposal's immutable plan; never reads current ERP facts. */
final class ApprovalEvidenceProjection {
    private ApprovalEvidenceProjection() {}

    static JsonNode project(JsonNode document) {
        var evidence = (ObjectNode) document.path("planEvidence");
        var snapshot = evidence.remove("sourceSnapshot");
        for (String key : new String[] {"supply", "boms", "historyStartDate", "safetyDays"}) {
            if (snapshot.has(key)) evidence.set(key, snapshot.get(key));
            else evidence.putNull(key);
        }
        if (snapshot.path("sourceFacts").isArray()) {
            var refs = evidence.putArray("sourceRefs");
            for (var fact : snapshot.path("sourceFacts")) {
                if (fact.hasNonNull("sourceRef")) refs.add(fact.get("sourceRef"));
            }
        } else evidence.putNull("sourceRefs");
        return document;
    }
}
