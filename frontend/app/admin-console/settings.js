"use client";
import { useEffect, useState } from "react";
import { api, write } from "./api";
import { Feedback } from "./shared";
export function Settings() {
  const [data, setData] = useState(null),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    api("/api/admin/settings")
      .then(setData)
      .catch((e) => setError(e.message));
  }, []);
  async function save(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setMessage("");
    try {
      setData(await write("/api/admin/settings", { ...data, reason }, "PUT"));
      setReason("");
      setMessage("점검 설정을 반영했어요.");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="control-heading">
        <div>
          <h1>운영 설정</h1>
          <p>점검 중에는 회원의 새 제출·실행·생성·설정 변경을 막습니다.</p>
        </div>
      </div>
      <div className="control-glass control-settings">
        <p>
          조회·로그인·로그아웃과 관리자 관리 기능은 유지됩니다. 이미 접수된
          러너·생성 작업은 계속 처리됩니다.
        </p>
        {data && (
          <form className="control-form" onSubmit={save}>
            <label className="control-check">
              <input
                type="checkbox"
                checked={data.maintenance}
                onChange={(e) =>
                  setData({ ...data, maintenance: e.target.checked })
                }
              />
              서비스 점검 모드
            </label>
            <label>
              점검 안내
              <input
                maxLength={300}
                value={data.message}
                onChange={(e) => setData({ ...data, message: e.target.value })}
              />
            </label>
            <label>
              변경 사유
              <textarea
                required
                minLength={4}
                maxLength={500}
                rows={3}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </label>
            <Feedback error={error} message={message} />
            <button className="primary" disabled={busy}>
              {busy ? "반영 중…" : "설정 반영"}
            </button>
          </form>
        )}
        {!data && <Feedback error={error} />}
      </div>
    </>
  );
}
