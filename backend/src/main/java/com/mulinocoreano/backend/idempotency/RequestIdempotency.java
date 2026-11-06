package com.mulinocoreano.backend.idempotency;

import com.mulinocoreano.backend.interfacepackage.CaseDto;
import com.mulinocoreano.backend.interfacepackage.InvalidInterfaceRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 동일 인간의 요청을 직렬화하고 Case 생성과 영수증을 함께 커밋한다. */
@Component
public class RequestIdempotency {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final com.mulinocoreano.backend.planning.CanonicalJson canonical;
    public RequestIdempotency(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; this.canonical = new com.mulinocoreano.backend.planning.CanonicalJson(mapper); }

    public CaseDto execute(String scope, String key, Object request, Supplier<CaseDto> action) {
        return mapper.treeToValue(executeJson(scope, key, request, action), CaseDto.class);
    }

    /** New operation namespaces use canonical hashes; legacy Case and plan receipts keep their hash. */
    public tools.jackson.databind.JsonNode executeCanonicalJson(String scope, String key, Object request, Supplier<?> action) {
        return executeHashed(scope, key, canonical.sha256(request), action, true);
    }

    public tools.jackson.databind.JsonNode executeJson(String scope, String key, Object request, Supplier<?> action) {
        return executeHashed(scope, key, hash(mapper.writeValueAsString(request)), action, false);
    }

    private tools.jackson.databind.JsonNode executeHashed(String scope, String key, String hash, Supplier<?> action, boolean exact) {
        if (key.isBlank() || key.length() > 200) throw new InvalidInterfaceRequestException("Invalid Idempotency-Key");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Transaction required");
        coordinate(scope, key);
        var saved = jdbc.sql("SELECT request_hash,response::text FROM request_idempotency WHERE scope=:scope AND request_key=:key")
                .param("scope", scope).param("key", key)
                .query((rs, row) -> new Receipt(rs.getString(1), rs.getString(2))).optional();
        if (saved.isPresent()) {
            if (!saved.get().hash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Key already used for different input");
            return exact ? canonical.readTree(saved.get().response()) : mapper.readTree(saved.get().response());
        }
        Object result = action.get();
        jdbc.sql("INSERT INTO request_idempotency(scope,request_key,request_hash,response) VALUES (:scope,:key,:hash,CAST(:response AS jsonb))")
                .param("scope", scope).param("key", key).param("hash", hash)
                .param("response", mapper.writeValueAsString(result)).update();
        return exact ? canonical.readTree(mapper.writeValueAsString(result)) : mapper.valueToTree(result);
    }
    public void coordinate(String scope, String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Transaction required");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:scope),hashtext(:key))")
                .param("scope", scope).param("key", key).query((rs, row) -> true).single();
    }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Receipt(String hash, String response) {}
}
