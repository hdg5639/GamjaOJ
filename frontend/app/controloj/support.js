"use client";
import { useEffect, useState } from "react";
import Modal from "../modal";
import SelectControl from "../select-control";
import { api, write } from "./api";
import { Feedback, date } from "./shared";
const labels = {
  OPEN: "접수됨",
  IN_PROGRESS: "확인 중",
  RESOLVED: "답변 완료",
};
export function Support() {
  const [result, setResult] = useState(null),
    [page, setPage] = useState(0),
    [filter, setFilter] = useState(""),
    [refresh, setRefresh] = useState(0),
    [edit, setEdit] = useState(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [loading, setLoading] = useState(false);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setResult(null);
    setError("");
    api("/api/admin/support?page=" + page + "&status=" + filter)
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
  }, [page, filter, refresh]);
  async function save(e) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await write(
        "/api/admin/support/" + edit.id,
        { status: edit.status, reply: edit.reply, revision: edit.revision },
        "PUT",
      );
      setEdit(null);
      setRefresh((v) => v + 1);
      setMessage("저장했어요. 회원의 내 문의에 반영됩니다.");
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
          <h1>문의·오류 제보</h1>
          <p>회원의 문의를 확인하고 답변과 처리 상태를 남겨요.</p>
        </div>
        <button
          className="secondary"
          disabled={loading}
          onClick={() => setRefresh((v) => v + 1)}
        >
          새로고침
        </button>
      </div>
      <label>
        처리 상태
        <SelectControl
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setPage(0);
          }}
        >
          <option value="">전체</option>
          {Object.entries(labels).map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </SelectControl>
      </label>
      <Feedback error={!edit ? error : ""} message={message} />
      {loading && <p role="status">문의를 불러오고 있어요.</p>}
      <div className="control-table-wrap">
        <table className="control-table">
          <thead>
            <tr>
              <th>제목</th>
              <th>회원</th>
              <th>종류</th>
              <th>상태</th>
              <th>접수</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {result?.items.map((item) => (
              <tr key={item.id}>
                <td>{item.title}</td>
                <td>{item.username}</td>
                <td>{item.kind}</td>
                <td>{labels[item.status]}</td>
                <td>{date(item.createdAt)}</td>
                <td>
                  <button
                    className="secondary"
                    onClick={() => {
                      setEdit({ ...item });
                      setError("");
                      setMessage("");
                    }}
                  >
                    확인·답변
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result && !result.items.length && <p>해당 상태의 문의가 없어요.</p>}
      <div className="support-pagination">
        <button
          className="secondary"
          disabled={page === 0 || loading}
          onClick={() => setPage((v) => v - 1)}
        >
          이전
        </button>
        <span>{page + 1}페이지</span>
        <button
          className="secondary"
          disabled={!result?.hasNext || loading}
          onClick={() => setPage((v) => v + 1)}
        >
          다음
        </button>
      </div>
      <Modal
        open={!!edit}
        title="문의 확인·답변"
        onClose={() => {
          if (!busy) setEdit(null);
        }}
        className="support-dialog"
      >
        {edit && (
          <form className="support-form" onSubmit={save}>
            <h4>{edit.title}</h4>
            <p className="muted">
              {edit.username} · {edit.kind} · {date(edit.createdAt)}
            </p>
            <p className="support-text">{edit.body}</p>
            <fieldset disabled={busy}>
              <label>
                처리 상태
                <SelectControl
                  aria-label="처리 상태"
                  value={edit.status}
                  onChange={(e) => setEdit({ ...edit, status: e.target.value })}
                >
                  {Object.entries(labels).map(([v, l]) => (
                    <option key={v} value={v}>
                      {l}
                    </option>
                  ))}
                </SelectControl>
              </label>
              <label>
                운영자 답변
                <textarea
                  aria-label="운영자 답변"
                  maxLength={4000}
                  rows={7}
                  required={edit.status === "RESOLVED"}
                  value={edit.reply}
                  onChange={(e) => setEdit({ ...edit, reply: e.target.value })}
                />
              </label>
              <Feedback error={error} />
              <button className="primary" disabled={busy}>
                {busy ? "저장 중…" : "답변·상태 저장"}
              </button>
            </fieldset>
          </form>
        )}
      </Modal>
    </>
  );
}
