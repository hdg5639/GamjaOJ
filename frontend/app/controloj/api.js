export async function api(path, options = {}) {
  const headers = new Headers(options.headers);
  if (options.method && !["GET", "HEAD"].includes(options.method)) {
    const response = await fetch("/api/auth/csrf", { cache: "no-store" });
    if (!response.ok) throw new Error("서버 연결을 확인해 주세요.");
    const token = await response.json();
    headers.set(token.headerName, token.token);
  }
  const response = await fetch(path, {
    ...options,
    headers,
    cache: "no-store",
  });
  const text = await response.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {}
  if (!response.ok) {
    const error = new Error(
      body?.message || "요청을 완료하지 못했어요. 다시 시도해 주세요.",
    );
    error.status = response.status;
    error.code = body?.code;
    if (
      path.startsWith("/api/admin/") &&
      !path.endsWith("/me") &&
      !path.endsWith("/session/verify") &&
      (error.code === "ADMIN_REAUTH_REQUIRED" ||
        error.status === 401 ||
        (error.status === 403 && error.code !== "CSRF_REJECTED"))
    )
      window.dispatchEvent(
        new CustomEvent("control-auth", {
          detail:
            error.code === "ADMIN_REAUTH_REQUIRED" ? "reauth" : error.status,
        }),
      );
    throw error;
  }
  return body;
}
export function write(path, body, method = "POST", headers = {}) {
  return api(path, {
    method,
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
}
