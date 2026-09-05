export function createApiClient({ token, base = process.env.MULINO_API_BASE ?? "http://localhost:8080/api/v1", timeoutMs = Number(process.env.MULINO_API_TIMEOUT_MS ?? "10000") }) {
  const timeout = Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 10000;
  return async (path, opts = {}) => {
    const method = (opts.method ?? "GET").toUpperCase();
    try {
      const res = await fetch(base.replace(/\/$/, "") + path, {
        ...opts,
        headers: { ...opts.headers, Authorization: `Bearer ${typeof token === "function" ? await token() : token}` },
        signal: AbortSignal.timeout(timeout),
        redirect: "error",
      });
      if (!res.ok) throw new Error("API " + res.status + ": 요청을 처리할 수 없습니다.");
      return await res.json();
    } catch (error) {
      if (error?.name === "TimeoutError" || error?.name === "AbortError") {
        const uncertain = !["GET", "HEAD", "OPTIONS"].includes(method)
          ? " 서버에서 요청이 반영되었을 수 있습니다. 자동 재시도하지 말고 현재 상태를 먼저 확인하세요."
          : "";
        throw new Error(`API 요청 시간 초과 (${timeout}ms).${uncertain}`);
      }
      if (error?.message?.startsWith("API ")) throw error;
      throw new Error("API 연결 또는 인증에 실패했습니다.");
    }
  };
}
