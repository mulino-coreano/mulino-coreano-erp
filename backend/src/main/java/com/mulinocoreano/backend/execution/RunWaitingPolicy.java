package com.mulinocoreano.backend.execution;

import com.mulinocoreano.backend.execution.RunExecutionService.Wait;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validates model-supplied waits inside the caller's locked execution transaction. */
final class RunWaitingPolicy {
    private final RunExecutionRepository repository;

    RunWaitingPolicy(RunExecutionRepository repository) {
        this.repository = repository;
    }

    List<Wait> normalize(RunLeaseRepository.RunRow row, List<Wait> waits) {
        if (waits.isEmpty() || waits.size() > 16) throw invalidResult();
        if (repository.hasActiveWait(row.workId())) throw invalidResult();
        var normalized = new java.util.ArrayList<Wait>();
        for (Wait wait : waits) {
            requireResultText(wait.reason(), 2000);
            if (wait.payload() == null) throw invalidResult();
            var payload = new java.util.LinkedHashMap<String, Object>(wait.payload());
            if ("DEPENDENCY_DONE".equals(wait.type())) {
                String reference =
                        coherentAlias(
                                wait.payload(),
                                "dependent_wi_ref",
                                "dependentWiRef",
                                value -> value);
                if (reference.equals(row.workRef())
                        || !repository.hasWorkItem(reference, row.caseId())) throw invalidResult();
                payload.remove("dependent_wi_ref");
                payload.put("dependentWiRef", reference);
            } else if ("SCHEDULED_TIME".equals(wait.type())) {
                String due = coherentAlias(wait.payload(), "due_at", "dueAt", this::strictInstant);
                payload.remove("due_at");
                payload.put("dueAt", due);
            } else {
                // Approval waits belong to the purchasing proposal transaction.
                throw invalidResult();
            }
            normalized.add(
                    new Wait(
                            wait.type(),
                            java.util.Collections.unmodifiableMap(payload),
                            wait.reason()));
        }
        return List.copyOf(normalized);
    }

    private String coherentAlias(
            Map<String, Object> payload,
            String first,
            String second,
            java.util.function.UnaryOperator<String> normalize) {
        String result = null;
        for (String alias : List.of(first, second)) {
            if (!payload.containsKey(alias)) continue;
            if (!(payload.get(alias) instanceof String value) || value.isBlank())
                throw invalidResult();
            String canonical = normalize.apply(value);
            if (result != null && !result.equals(canonical)) throw invalidResult();
            result = canonical;
        }
        if (result == null) throw invalidResult();
        return result;
    }

    private String strictInstant(String value) {
        // Instant.parse permits 24:00 and leap-second normalization. Scheduled business
        // deadlines instead require an unambiguous calendar date and 00:00:00..23:59:59.
        if (!value.matches(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt](?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})"))
            throw invalidResult();
        try {
            return Instant.parse(value).toString();
        } catch (java.time.format.DateTimeParseException invalid) {
            throw invalidResult();
        }
    }

    private static void requireResultText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalidResult();
    }

    private static ResponseStatusException invalidResult() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RESULT");
    }
}
