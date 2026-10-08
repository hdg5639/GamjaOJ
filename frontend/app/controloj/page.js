"use client";
import { useEffect, useState } from "react";
import ThemeToggle from "../theme-toggle";
import LoadingIndicator from "../loading-indicator";
import Modal from "../modal";
import { api, write } from "./api";
import {
  Overview,
  Notices,
  Records,
  Availability,
  Settings,
  Feedback,
} from "./sections";
import "./control.css";
import "../support-center.css";
import { Support } from "./support";
const menus = [
  ["overview", "운영 현황"],
  ["notices", "공지·업데이트"],
  ["support", "문의·오류 제보"],
  ["members", "회원·권한"],
  ["problems", "문제은행"],
  ["availability", "진단·코스 제공"],
  ["training", "회원 훈련"],
  ["plans", "맞춤 계획"],
  ["jobs", "작업 관리"],
  ["settings", "운영 설정"],
  ["audit", "감사 이력"],
];
export default function ControlOJ() {
  const [phase, setPhase] = useState("loading"),
    [user, setUser] = useState(null),
    [section, setSection] = useState("overview"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [reauth, setReauth] = useState(false);
  async function identify() {
    try {
      const identity = await api("/api/admin/me");
      setUser(identity);
      if (identity.verified) {
        setPhase("ready");
        setReauth(false);
      } else if (phase === "ready") setReauth(true);
      else setPhase("verify");
    } catch (e) {
      if ([401, 403].includes(e.status)) {
        setUser(null);
        setPhase(e.status === 401 ? "login" : "denied");
      } else {
        setError(e.message);
        setPhase("error");
      }
    }
  }
  useEffect(() => {
    document.title = "ControlOJ · 운영 콘솔";
    identify();
    const expired = (e) => {
      if (e.detail === 401) {
        setUser(null);
        setPhase("login");
        setReauth(false);
      } else if (e.detail === 403) {
        setUser(null);
        setPhase("denied");
        setReauth(false);
      } else setReauth(true);
    };
    window.addEventListener("control-auth", expired);
    return () => window.removeEventListener("control-auth", expired);
  }, []);
  async function login(event) {
    event.preventDefault();
    if (busy) return;
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError("");
    try {
      await api("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams(form),
      });
      const identity = await api("/api/admin/me");
      setUser(identity);
      const verified = await write("/api/admin/session/verify", {
        password: form.get("password"),
      });
      setUser(verified);
      setPhase("ready");
      event.target?.reset?.();
    } catch (e) {
      setError(e.message);
      if (e.status === 403) setPhase("denied");
    } finally {
      setBusy(false);
    }
  }
  async function verify(event) {
    event.preventDefault();
    if (busy) return;
    const form = event.currentTarget,
      password = new FormData(form).get("password");
    setBusy(true);
    setError("");
    try {
      const identity = await write("/api/admin/session/verify", { password });
      setUser(identity);
      setPhase("ready");
      setReauth(false);
      form.reset();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function logout() {
    setBusy(true);
    setError("");
    try {
      await api("/api/auth/logout", { method: "POST" });
      setUser(null);
      setPhase("login");
      setReauth(false);
      setSection("overview");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  function verification() {
    return (
      <form className="control-form" onSubmit={verify}>
        <p>
          민감한 운영 작업을 위해 비밀번호를 다시 확인해 주세요. 작성 중인
          내용은 유지됩니다.
        </p>
        <label>
          비밀번호
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            required
            maxLength={100}
          />
        </label>
        <Feedback error={error} />
        <button className="primary" disabled={busy}>
          {busy ? "확인 중…" : "관리자 재인증"}
        </button>
      </form>
    );
  }
  return (
    <div className="control-shell">
      <header className="control-header">
        <a href="/" className="control-brand">
          <img src="/gamjaoj-favicon.svg" alt="" width="30" height="30" />
          Control<span>OJ</span>
        </a>
        <div className="control-header-actions">
          <ThemeToggle />
          {user && (
            <>
              <span>{user.username}</span>
              <button className="secondary" disabled={busy} onClick={logout}>
                로그아웃
              </button>
            </>
          )}
        </div>
      </header>
      {phase === "ready" ? (
        <div className="control-workspace">
          <nav className="control-nav" aria-label="운영 관리">
            {menus.map(([key, label]) => (
              <button
                key={key}
                className={section === key ? "selected" : ""}
                aria-current={section === key ? "page" : undefined}
                onClick={() => setSection(key)}
              >
                {label}
              </button>
            ))}
            <p>
              재인증 유효 15분
              <br />
              미사용 5분 후 재확인
            </p>
          </nav>
          <main className="control-content" key={user.username}>
            {section === "overview" ? (
              <Overview />
            ) : section === "notices" ? (
              <Notices />
            ) : section === "support" ? (
              <Support />
            ) : section === "availability" ? (
              <Availability />
            ) : section === "settings" ? (
              <Settings />
            ) : (
              <Records
                key={section}
                kind={section}
                currentUser={user.username}
              />
            )}
          </main>
        </div>
      ) : (
        <main className="control-main">
          {phase === "loading" ? (
            <div className="control-entry" role="status">
              <LoadingIndicator />
              운영 콘솔 연결 중
            </div>
          ) : phase === "login" ? (
            <section className="control-entry control-glass">
              <p className="control-eyebrow">GAMJAOJ OPERATIONS</p>
              <h1>운영 콘솔 로그인</h1>
              <p>관리자 계정으로 접속하세요.</p>
              <form onSubmit={login}>
                <label>
                  아이디
                  <input
                    name="username"
                    autoComplete="username"
                    required
                    maxLength={24}
                  />
                </label>
                <label>
                  비밀번호
                  <input
                    name="password"
                    type="password"
                    autoComplete="current-password"
                    required
                    maxLength={100}
                  />
                </label>
                <button className="primary" disabled={busy}>
                  {busy ? "확인 중…" : "ControlOJ 로그인"}
                </button>
              </form>
              <Feedback error={error} />
            </section>
          ) : phase === "verify" ? (
            <section className="control-entry control-glass">
              <h1>관리자 재인증</h1>
              {verification()}
            </section>
          ) : phase === "denied" ? (
            <section className="control-entry control-glass">
              <h1>관리자 권한이 필요해요</h1>
              <p>이 계정은 운영 콘솔 접근 권한이 없어요.</p>
              <button className="secondary" onClick={logout} disabled={busy}>
                다른 계정으로 로그인
              </button>
            </section>
          ) : (
            <section className="control-entry control-glass">
              <h1>연결을 확인해 주세요</h1>
              <Feedback error={error} />
              <button className="secondary" onClick={identify}>
                다시 시도
              </button>
            </section>
          )}
        </main>
      )}
      <Modal
        open={reauth}
        title="관리자 재인증"
        onClose={() => {
          if (!busy) setReauth(false);
        }}
        description="인증하면 작성 중인 작업을 이어갈 수 있어요."
      >
        {verification()}
        <button className="secondary" onClick={logout} disabled={busy}>
          로그아웃
        </button>
      </Modal>
      <footer className="control-footer">ControlOJ · GamjaOJ 운영 콘솔</footer>
    </div>
  );
}
