"use client";
import { useEffect, useRef, useState } from "react";
import Modal from "./modal";
import SelectControl from "./select-control";
import "./support-center.css";
export const supportStatus = {
  OPEN: "접수됨",
  IN_PROGRESS: "확인 중",
  RESOLVED: "답변 완료",
};
async function request(path, options = {}) {
  const headers = new Headers(options.headers);
  if (options.method) {
    const r = await fetch("/api/auth/csrf", { cache: "no-store" });
    if (!r.ok) throw new Error("로그인 상태와 서버 연결을 확인해 주세요.");
    const c = await r.json();
    headers.set(c.headerName, c.token);
  }
  const r = await fetch(path, { ...options, headers, cache: "no-store" });
  const body = await r.json().catch(() => null);
  if (!r.ok)
    throw new Error(
      r.status === 401
        ? "로그인 후 다시 보내 주세요."
        : body?.message || "요청을 완료하지 못했어요. 다시 시도해 주세요.",
    );
  return body;
}
export default function SupportCenter({ accountId }) {
  const [open, setOpen] = useState(false),
    [tab, setTab] = useState("write"),
    [draft, setDraft] = useState({ kind: "오류 제보", title: "", body: "" }),
    [result, setResult] = useState(null),
    [page, setPage] = useState(0),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(false),
    [refresh, setRefresh] = useState(0);
  const key = useRef(null),
    flight = useRef(false);
  const loggedIn = accountId !== "anonymous";
  useEffect(() => {
    const show = () => setOpen(true);
    window.addEventListener("gamjaoj-support", show);
    return () => window.removeEventListener("gamjaoj-support", show);
  }, []);
  useEffect(() => {
    if (!open || tab !== "history" || !loggedIn) return;
    let active = true;
    setLoading(true);
    setError("");
    setResult(null);
    request("/api/support?page=" + page)
      .then((v) => {
        if (active) setResult(v);
      })
      .catch((e) => {
        if (active) setError(e.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [open, tab, page, refresh, loggedIn]);
  function change(field, value) {
    setDraft((d) => ({ ...d, [field]: value }));
    key.current = null;
    setMessage("");
  }
  async function send(e) {
    e.preventDefault();
    if (flight.current) return;
    flight.current = true;
    setBusy(true);
    setError("");
    setMessage("");
    key.current ||= crypto.randomUUID();
    try {
      await request("/api/support", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": key.current,
        },
        body: JSON.stringify(draft),
      });
      setDraft({ kind: "오류 제보", title: "", body: "" });
      key.current = null;
      setPage(0);
      setRefresh((v) => v + 1);
      setMessage(
        "문의가 접수됐어요. 내 문의에서 처리 상태와 답변을 확인할 수 있어요.",
      );
    } catch (e) {
      setError(e.message);
    } finally {
      flight.current = false;
      setBusy(false);
    }
  }
  return (
    <>
      <button
        type="button"
        className="secondary support-trigger"
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        title="운영자 문의"
        aria-label="문의·제보"
      >
        <svg
          width="18"
          height="18"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M4 4h16v12H9l-5 4V4Z" />
          <path d="M8 8h8M8 12h5" />
        </svg>
      </button>
      <Modal
        open={open}
        title="운영자 문의"
        description="이용 중 불편한 점이나 개선 아이디어를 알려 주세요."
        className="support-dialog"
        onClose={() => {
          if (!busy) setOpen(false);
        }}
      >
        {!loggedIn ? (
          <p>
            문의는 로그인 후 보낼 수 있어요. 로그인하면 이곳에서 문의와 답변을
            확인할 수 있습니다.
          </p>
        ) : (
          <>
            <nav className="support-tabs" aria-label="문의 메뉴">
              <button
                type="button"
                className={tab === "write" ? "selected" : "secondary"}
                aria-pressed={tab === "write"}
                onClick={() => {
                  setTab("write");
                  setError("");
                }}
              >
                문의 보내기
              </button>
              <button
                type="button"
                className={tab === "history" ? "selected" : "secondary"}
                aria-pressed={tab === "history"}
                onClick={() => {
                  setTab("history");
                  setError("");
                }}
              >
                내 문의
              </button>
            </nav>
            {error && <p role="alert">{error}</p>}
            {message && <p role="status">{message}</p>}
            {tab === "write" ? (
              <form className="support-form" onSubmit={send}>
                <fieldset disabled={busy}>
                  <label>
                    문의 종류
                    <SelectControl
                      aria-label="문의 종류"
                      value={draft.kind}
                      onChange={(e) => change("kind", e.target.value)}
                    >
                      {["오류 제보", "이용 문의", "개선 제안"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </SelectControl>
                  </label>
                  <label>
                    제목
                    <input
                      required
                      maxLength={120}
                      value={draft.title}
                      onChange={(e) => change("title", e.target.value)}
                      placeholder="어떤 점이 불편했나요?"
                    />
                  </label>
                  <label>
                    내용
                    <textarea
                      aria-label="내용"
                      required
                      maxLength={4000}
                      rows={7}
                      value={draft.body}
                      onChange={(e) => change("body", e.target.value)}
                      placeholder="문제 이름, 발생 상황, 기대한 동작을 알려 주시면 확인에 도움이 돼요."
                    />
                  </label>
                  <p className="muted">
                    비밀번호, 인증 코드, API 키 등 비밀 정보는 적지 마세요.
                  </p>
                  <button
                    className="primary"
                    disabled={busy || !draft.title.trim() || !draft.body.trim()}
                  >
                    {busy ? "보내는 중…" : "문의 보내기"}
                  </button>
                </fieldset>
              </form>
            ) : (
              <>
                <button
                  type="button"
                  className="secondary"
                  disabled={loading}
                  onClick={() => setRefresh((v) => v + 1)}
                >
                  새로고침
                </button>
                {loading && <p role="status">문의를 불러오고 있어요.</p>}
                {result && !result.items.length && (
                  <p>아직 보낸 문의가 없어요.</p>
                )}
                <div className="support-list">
                  {result?.items.map((item) => (
                    <details key={item.id}>
                      <summary>
                        <strong>{item.title}</strong>
                        <span>
                          {supportStatus[item.status]} ·{" "}
                          {new Date(item.createdAt).toLocaleDateString("ko-KR")}
                        </span>
                      </summary>
                      <p className="support-text">{item.body}</p>
                      {item.reply ? (
                        <div className="support-reply">
                          <strong>운영자 답변</strong>
                          <p className="support-text">{item.reply}</p>
                        </div>
                      ) : (
                        <p className="muted">
                          운영자가 확인하면 여기에 답변이 표시돼요.
                        </p>
                      )}
                    </details>
                  ))}
                </div>
                <div className="support-pagination">
                  <button
                    type="button"
                    className="secondary"
                    disabled={page === 0 || loading}
                    onClick={() => setPage((v) => v - 1)}
                  >
                    이전
                  </button>
                  <span>{page + 1}페이지</span>
                  <button
                    type="button"
                    className="secondary"
                    disabled={!result?.hasNext || loading}
                    onClick={() => setPage((v) => v + 1)}
                  >
                    다음
                  </button>
                </div>
              </>
            )}
          </>
        )}
      </Modal>
    </>
  );
}
