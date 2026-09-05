import { createRemoteJWKSet, customFetch, jwtVerify } from "jose";

const ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

export class AuthorizationError extends Error {
  constructor(status, code, scope = "erp:read") {
    super(code);
    this.status = status;
    this.code = code;
    this.scope = scope;
  }
}

export function requireScope(identity, scope) {
  if (!identity.scopes.includes(scope)) throw new AuthorizationError(403, "insufficient_scope", scope);
}

export function createAuth0(config, authFetch = fetch) {
  const keys = createRemoteJWKSet(new URL(".well-known/jwks.json", config.issuer), {
    timeoutDuration: config.timeoutMs,
    [customFetch]: (url, options) => authFetch(url, { ...options, redirect: "error" }),
  });

  async function verify(token, audience) {
    const { payload } = await jwtVerify(token, keys, {
      issuer: config.issuer,
      audience,
      algorithms: ["RS256"],
      requiredClaims: ["iss", "aud", "sub", "exp"],
    });
    if (typeof payload.sub !== "string" || !payload.sub.trim() || payload.gty === "client-credentials" || payload.sub.endsWith("@clients")) {
      throw new Error("Human access token required");
    }
    return { subject: payload.sub, scopes: typeof payload.scope === "string" ? payload.scope.split(/\s+/).filter(Boolean) : [] };
  }

  return {
    async authenticate(header) {
      const match = typeof header === "string" && /^Bearer ([^\s]+)$/i.exec(header);
      if (!match) throw new AuthorizationError(401, "invalid_token");
      try {
        const identity = await verify(match[1], config.audience);
        return { ...identity, token: match[1] };
      } catch {
        throw new AuthorizationError(401, "invalid_token");
      }
    },
    async exchange(identity, scope) {
      requireScope(identity, scope);
      try {
        const response = await authFetch(new URL("oauth/token", config.issuer), {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "urn:ietf:params:oauth:grant-type:token-exchange",
            subject_token_type: ACCESS_TOKEN_TYPE,
            requested_token_type: ACCESS_TOKEN_TYPE,
            subject_token: identity.token,
            client_id: config.clientId,
            client_secret: config.clientSecret,
            audience: config.apiAudience,
            scope,
          }),
          signal: AbortSignal.timeout(config.timeoutMs),
          redirect: "error",
        });
        if (!response.ok) throw new Error("Exchange rejected");
        const result = await response.json();
        if (result.token_type !== "Bearer" || result.issued_token_type !== ACCESS_TOKEN_TYPE || typeof result.access_token !== "string" || !Number.isFinite(result.expires_in) || result.expires_in <= 0) {
          throw new Error("Invalid exchange response");
        }
        const downstream = await verify(result.access_token, config.apiAudience);
        if (downstream.subject !== identity.subject) throw new Error("Exchange subject changed");
        requireScope(downstream, scope);
        return result.access_token;
      } catch {
        // Never include Auth0 response bodies, JWTs or client credentials in tool errors/logs.
        throw new Error("사용자 위임 토큰 교환에 실패했습니다. Auth0 OBO 설정과 API 권한을 확인하세요.");
      }
    },
  };
}
