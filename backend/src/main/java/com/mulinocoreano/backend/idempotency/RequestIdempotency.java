package com.mulinocoreano.backend.idempotency;

import static com.mulinocoreano.backend.generated.Tables.REQUEST_IDEMPOTENCY;

import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.interfacepackage.InvalidInterfaceRequestException;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.SQLDataType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Replays business-write receipts inside the caller's transaction. Lease credential issuance uses
 * its separate non-replayable protocol and must never persist raw tokens here.
 */
@Component
public class RequestIdempotency {
    private final DSLContext dsl;
    private final CanonicalJson json;

    public RequestIdempotency(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public JsonNode execute(String scope, String key, Object request, Supplier<?> action) {
        validate(scope, "scope");
        validate(key, "Idempotency-Key");
        Objects.requireNonNull(action, "action");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotent writes require a caller transaction");
        }
        String requestHash = json.sha256(request);
        coordinate(scope, key);
        var receipts = REQUEST_IDEMPOTENCY;
        var saved =
                dsl.select(receipts.REQUEST_HASH, receipts.RESPONSE)
                        .from(receipts)
                        .where(receipts.SCOPE.eq(scope).and(receipts.REQUEST_KEY.eq(key)))
                        .fetchOptional(row -> new Receipt(row.value1(), row.value2().data()));
        if (saved.isPresent()) {
            if (!saved.get().requestHash().equals(requestHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key was already used for different input");
            }
            return json.readTree(saved.get().response());
        }
        String response = json.write(action.get());
        JsonNode tree = json.readTree(response);
        if (!tree.isObject())
            throw new IllegalStateException("Idempotent business receipts must be JSON objects");
        dsl.insertInto(receipts)
                .set(receipts.SCOPE, scope)
                .set(receipts.REQUEST_KEY, key)
                .set(receipts.REQUEST_HASH, requestHash)
                .set(receipts.RESPONSE, JSONB.valueOf(response))
                .execute();
        return tree;
    }

    /** Shared resource ordering for operations whose coordination must precede entity locks. */
    public void coordinate(String scope, String key) {
        validate(scope, "scope");
        validate(key, "key");
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Coordination requires a caller transaction");
        // Hash collisions only serialize unrelated requests. Actual keys and SHA-256 remain
        // authoritative; RR callers retry the whole transaction after a serialization failure.
        var scopeHash = function(name("hashtext"), Integer.class, val(scope));
        var keyHash = function(name("hashtext"), Integer.class, val(key));
        var lock = function(name("pg_advisory_xact_lock"), SQLDataType.OTHER, scopeHash, keyHash);
        dsl.select(lock).fetchSingle();
    }

    private static void validate(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200)
            throw new InvalidInterfaceRequestException(name + " must contain 1..200 characters");
    }

    private record Receipt(String requestHash, String response) {}
}
