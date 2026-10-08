"use client";
import { useEffect, useState } from "react";
import Modal from "../modal";
import { api, write } from "./api";
import { Feedback } from "./shared";
import CourseEditor from "./course-editor";
export function Availability() {
  const [data, setData] = useState(null),
    [error, setError] = useState(""),
    [action, setAction] = useState(null),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [course, setCourse] = useState(null);
  async function load() {
    try {
      setData(await api("/api/admin/availability"));
    } catch (e) {
      setError(e.message);
    }
  }
  useEffect(() => {
    load();
  }, []);
  async function save(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await write(
        "/api/admin/availability/" +
          action.kind +
          "/" +
          encodeURIComponent(action.item.id),
        { enabled: !action.enabled, revision: action.revision, reason },
        "PUT",
      );
      setAction(null);
      await load();
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
          <h1>진단·코스 제공</h1>
          <p>
            새 진단과 코스 등록을 조절해요. 이미 시작한 학습 기록은 유지됩니다.
          </p>
        </div>
        <div className="control-inline">
          <button className="secondary" onClick={load}>
            새로고침
          </button>
          <button
            className="primary"
            onClick={() =>
              setCourse({
                new: true,
                id: "",
                title: "",
                kind: "알고리즘 훈련",
                summary: "",
                prerequisite: "",
                notice: "",
                revision: 0,
                stages: [{ title: "", goal: "", versions: [] }],
              })
            }
          >
            새 훈련 코스
          </button>
        </div>
      </div>
      <Feedback error={!action ? error : ""} />
      {data &&
        [
          ["banks", "진단 문제은행", "bank"],
          ["courses", "훈련 코스", "course"],
        ].map(([key, title, kind]) => (
          <section className="control-section control-availability" key={key}>
            <h2>{title}</h2>
            <div className="control-table-wrap">
              <table className="control-table">
                <thead>
                  <tr>
                    <th>이름·ID</th>
                    <th>상태</th>
                    <th>정보</th>
                    <th>관리</th>
                  </tr>
                </thead>
                <tbody>
                  {data[key].map((item) => {
                    const enabled =
                        kind === "bank" ? item.admin_enabled : item.enabled,
                      revision =
                        kind === "bank" ? item.admin_revision : item.revision;
                    return (
                      <tr key={item.id}>
                        <th>{item.id}</th>
                        <td>
                          {kind === "bank" && !item.reviewed
                            ? "검증 전"
                            : enabled
                              ? "제공 중"
                              : "신규 이용 중단"}
                        </td>
                        <td>
                          {kind === "bank"
                            ? `${item.problems}문제 · 진행 ${item.active}명`
                            : "기존 코스 구성 유지"}
                        </td>
                        <td>
                          <button
                            className="secondary"
                            disabled={
                              kind === "bank" && !item.reviewed && !enabled
                            }
                            onClick={() => {
                              setAction({ kind, item, enabled, revision });
                              setReason("");
                              setError("");
                            }}
                          >
                            {enabled ? "신규 이용 중단" : "다시 제공"}
                          </button>
                          {kind === "course" && (
                            <button
                              className="secondary"
                              onClick={() =>
                                setCourse({
                                  ...data.definitions.find(
                                    (c) => c.id === item.id,
                                  ),
                                  revision: item.revision,
                                })
                              }
                            >
                              구성 편집
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </section>
        ))}
      <Modal
        open={!!action}
        title="제공 상태 변경"
        onClose={() => {
          if (!busy) setAction(null);
        }}
      >
        {action && (
          <form className="control-form" onSubmit={save}>
            <p>
              {action.item.id} ·{" "}
              {action.enabled ? "신규 이용을 중단합니다." : "다시 제공합니다."}
            </p>
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
            <Feedback error={error} />
            <button className="primary" disabled={busy}>
              확인·반영
            </button>
          </form>
        )}
      </Modal>
      {course && (
        <CourseEditor
          initial={course}
          onClose={() => setCourse(null)}
          onSaved={load}
        />
      )}
    </>
  );
}
