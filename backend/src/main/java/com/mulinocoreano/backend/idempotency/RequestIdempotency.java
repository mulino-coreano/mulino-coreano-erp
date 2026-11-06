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
    public RequestIdempotency(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public CaseDto execute(String scope, String key, Object request, Supplier<CaseDto> action) {
        if (key.isBlank() || key.length() > 200) throw new InvalidInterfaceRequestException("Invalid Idempotency-Key");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Transaction required");
        String hash = hash(mapper.writeValueAsString(request));
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:scope),hashtext(:key))")
                .param("scope", scope).param("key", key).query((rs, row) -> true).single();
        var saved = jdbc.sql("SELECT request_hash,response::text FROM request_idempotency WHERE scope=:scope AND request_key=:key")
                .param("scope", scope).param("key", key)
                .query((rs, row) -> new Receipt(rs.getString(1), rs.getString(2))).optional();
        if (saved.isPresent()) {
            if (!saved.get().hash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Key already used for different input");
            return mapper.readValue(saved.get().response(), CaseDto.class);
        }
        CaseDto result = action.get();
        jdbc.sql("INSERT INTO request_idempotency(scope,request_key,request_hash,response) VALUES (:scope,:key,:hash,CAST(:response AS jsonb))")
                .param("scope", scope).param("key", key).param("hash", hash)
                .param("response", mapper.writeValueAsString(result)).update();
        return result;
    }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Receipt(String hash, String response) {}
}
