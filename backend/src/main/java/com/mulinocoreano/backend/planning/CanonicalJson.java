package com.mulinocoreano.backend.planning;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON for immutable evidence: sorted object keys and exact decimal numbers. */
@Component
public class CanonicalJson {
    private final ObjectMapper mapper;

    public CanonicalJson(ObjectMapper mapper) { this.mapper = mapper; }

    public String write(Object value) {
        rejectNonFinite(value);
        return render(mapper.valueToTree(value));
    }

    public String sha256(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public JsonNode readTree(String value) {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
    }

    private String render(JsonNode node) {
        if (node.isObject()) {
            var values = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            return "{" + String.join(",", values.entrySet().stream()
                    .map(entry -> mapper.writeValueAsString(entry.getKey()) + ":" + render(entry.getValue())).toList()) + "}";
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<String>();
            node.forEach(element -> values.add(render(element)));
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !node.isBigDecimal() && !Double.isFinite(node.doubleValue()))
                throw new IllegalArgumentException("JSON_NUMBER_MUST_BE_FINITE");
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        return mapper.writeValueAsString(node);
    }

    private static void rejectNonFinite(Object value) {
        if (value instanceof Double number && !Double.isFinite(number)
                || value instanceof Float floatNumber && !Float.isFinite(floatNumber))
            throw new IllegalArgumentException("JSON_NUMBER_MUST_BE_FINITE");
        if (value instanceof Map<?, ?> map) map.values().forEach(CanonicalJson::rejectNonFinite);
        if (value instanceof Iterable<?> iterable) iterable.forEach(CanonicalJson::rejectNonFinite);
    }
}
