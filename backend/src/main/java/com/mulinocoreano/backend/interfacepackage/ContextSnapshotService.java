package com.mulinocoreano.backend.interfacepackage;

import org.springframework.stereotype.Service;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ContextSnapshotService {

    private final ContextSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public ContextSnapshotService(ContextSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Reconstructs the execution context from current database state. A Run must call this method
     * at creation time; stored snapshots are audit records, not a cache.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> build(String caseRef) {
        String json = repository.reconstruct(caseRef);

        Map<String, Object> snapshot =
                objectMapper
                        .readerFor(Map.class)
                        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readValue(json);
        return new LinkedHashMap<>(snapshot);
    }
}
