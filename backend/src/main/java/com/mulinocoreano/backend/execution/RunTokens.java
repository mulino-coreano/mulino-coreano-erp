package com.mulinocoreano.backend.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class RunTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RunTokens() {}
    static String issue() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String token) {
        if(token==null) return "";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static boolean matches(String token,String hash) {
        return hash!=null && MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII));
    }
}
