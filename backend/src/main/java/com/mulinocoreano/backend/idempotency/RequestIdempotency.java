package com.mulinocoreano.backend.idempotency;

import com.mulinocoreano.backend.planning.CanonicalJson;
import com.mulinocoreano.backend.interfacepackage.InvalidInterfaceRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.function.Supplier;

/** Replays business-write receipts inside the caller's transaction. Lease credential issuance
 * uses its separate non-replayable protocol and must never persist raw tokens here. */
@Component
public class RequestIdempotency {
    private final JdbcClient jdbc;
    private final CanonicalJson json;
    public RequestIdempotency(JdbcClient jdbc, CanonicalJson json) { this.jdbc = jdbc; this.json = json; }
    public JsonNode execute(String scope, String key, Object request, Supplier<?> action) {
        validate(scope, "scope");
        validate(key, "Idempotency-Key");
        Objects.requireNonNull(action, "action");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Idempotent writes require a caller transaction");
        }
        String requestHash = json.sha256(request);
        coordinate(scope,key);
        var saved = jdbc.sql("SELECT request_hash,response::text FROM request_idempotency WHERE scope=:scope AND request_key=:key")
                .param("scope", scope).param("key", key)
                .query((rs, row) -> new Receipt(rs.getString(1), rs.getString(2))).optional();
        if (saved.isPresent()) {
            if (!saved.get().requestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for different input");
            }
            return json.readTree(saved.get().response());
        }
        String response = json.write(action.get());
        JsonNode tree = json.readTree(response);
        if (!tree.isObject()) throw new IllegalStateException("Idempotent business receipts must be JSON objects");
        jdbc.sql("INSERT INTO request_idempotency(scope,request_key,request_hash,response) VALUES (:scope,:key,:hash,CAST(:response AS jsonb))")
                .param("scope", scope).param("key", key).param("hash", requestHash).param("response", response).update();
        return tree;
    }
    /** Shared resource ordering for operations whose coordination must precede entity locks. */
    public void coordinate(String scope,String key) {
        validate(scope,"scope");validate(key,"key");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Coordination requires a caller transaction");
        // Hash collisions only serialize unrelated requests. Actual keys and SHA-256 remain
        // authoritative; RR callers retry the whole transaction after a serialization failure.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:scope),hashtext(:key))")
                .param("scope",scope).param("key",key).query((rs,row)->true).single();
    }
    private static void validate(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200) throw new InvalidInterfaceRequestException(name + " must contain 1..200 characters");
    }
    private record Receipt(String requestHash, String response) {}
}
