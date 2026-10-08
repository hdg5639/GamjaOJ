"use client";
import { useEffect, useRef, useState } from "react";
import Modal from "../modal";
import SelectControl from "../select-control";
import { api, write } from "./api";
import { Feedback, date } from "./shared";
const emptyNotice = {
  kind: "공지",
  title: "",
  summary: "",
  body: "",
  pinned: false,
  published: false,
  revision: 0,
};
export function Notices() {
  const [items, setItems] = useState([]),
    [edit, setEdit] = useState(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState("");
  const key = useRef(null);
  async function load() {
    try {
      setItems(await api("/api/admin/announcements"));
    } catch (e) {
      setError(e.message);
    }
  }
  useEffect(() => {
    load();
  }, []);
  function start(item) {
    key.current = crypto.randomUUID();
    setEdit(item ? { ...item } : { ...emptyNotice });
    setError("");
    setMessage("");
  }
  async function save(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const { id, ...body } = edit;
      const saved = await write(
        id
          ? "/api/admin/announcements/" + encodeURIComponent(id)
          : "/api/admin/announcements",
        body,
        id ? "PUT" : "POST",
        id ? {} : { "Idempotency-Key": key.current },
      );
      setEdit(saved);
      await load();
      setMessage(
        saved.published
          ? "공지를 게시했어요. 회원 소식 목록에 반영됩니다."
          : "초안을 저장했어요. 회원에게는 보이지 않습니다.",
      );
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
          <h1>공지·업데이트</h1>
          <p>초안 작성부터 게시 취소까지 여기서 관리해요.</p>
        </div>
        <button className="primary" onClick={() => start(null)}>
          새 공지
        </button>
      </div>
      <div className="control-table-wrap">
        <table className="control-table">
          <thead>
            <tr>
              <th>제목</th>
              <th>종류</th>
              <th>게시 상태</th>
              <th>최근 수정</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id}>
                <th>
                  {item.pinned && (
                    <span className="control-muted">고정 · </span>
                  )}
                  {item.title}
                </th>
                <td>{item.kind}</td>
                <td>{item.published ? "게시 중" : "초안"}</td>
                <td>{date(item.updatedAt)}</td>
                <td>
                  <button className="secondary" onClick={() => start(item)}>
                    편집
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!items.length && (
          <p className="control-empty">
            공지가 없어요. 새 공지를 작성해 주세요.
          </p>
        )}
      </div>
      <Feedback error={!edit ? error : ""} />
      <Modal
        open={!!edit}
        title={edit?.id ? "공지 편집" : "새 공지"}
        wide
        onClose={() => {
          if (!busy) setEdit(null);
        }}
        description="본문은 일반 텍스트로 표시됩니다. 빈 줄로 문단을 나눠 주세요."
      >
        {edit && (
          <form className="control-form" onSubmit={save}>
            <div className="control-form-columns">
              <label>
                종류
                <SelectControl
                  value={edit.kind}
                  onChange={(e) => setEdit({ ...edit, kind: e.target.value })}
                >
                  {["공지", "새 기능", "업데이트"].map((k) => (
                    <option key={k}>{k}</option>
                  ))}
                </SelectControl>
              </label>
              <label>
                제목
                <input
                  value={edit.title}
                  required
                  maxLength={120}
                  onChange={(e) => setEdit({ ...edit, title: e.target.value })}
                />
              </label>
            </div>
            <label>
              목록 요약
              <textarea
                rows={2}
                required
                maxLength={400}
                value={edit.summary}
                onChange={(e) => setEdit({ ...edit, summary: e.target.value })}
              />
            </label>
            <label>
              본문
              <textarea
                rows={8}
                required
                maxLength={20000}
                value={edit.body}
                onChange={(e) => setEdit({ ...edit, body: e.target.value })}
              />
            </label>
            <div className="control-inline">
              <label className="control-check">
                <input
                  type="checkbox"
                  checked={edit.pinned}
                  onChange={(e) =>
                    setEdit({ ...edit, pinned: e.target.checked })
                  }
                />
                상단 고정
              </label>
              <label className="control-check">
                <input
                  type="checkbox"
                  checked={edit.published}
                  onChange={(e) =>
                    setEdit({ ...edit, published: e.target.checked })
                  }
                />
                회원에게 게시
              </label>
            </div>
            <details className="control-preview">
              <summary>회원에게 보이는 본문 미리보기</summary>
              <h3>{edit.title || "제목"}</h3>
              {edit.body.split(/\n\s*\n/).map((p, i) => (
                <p key={i}>{p}</p>
              ))}
            </details>
            <Feedback error={error} message={message} />
            <div className="control-form-actions">
              <button className="primary" disabled={busy}>
                {busy ? "저장 중…" : edit.published ? "저장·게시" : "초안 저장"}
              </button>
            </div>
          </form>
        )}
      </Modal>
    </>
  );
}
