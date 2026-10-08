"use client";
import { useEffect, useRef, useState } from "react";
import Modal from "../modal";
import SelectControl from "../select-control";
import { api, write } from "./api";
import { Feedback, date, status, parseJSON, parseTags } from "./shared";
const columns = {
  plans: ["회원", "계획 ID", "단계 수", "상태", "관리"],
  members: ["회원", "권한", "상태", "활성 세션", "가입일", "관리"],
  problems: ["문제", "분야", "상태", "공개 범위", "관리"],
  training: ["회원", "문제·목표", "상태", "시작일", "관리"],
  jobs: ["종류", "회원", "상태", "생성일", "관리"],
  audit: ["시간", "관리자", "작업·대상", "사유", "변경 내역"],
};
const headings = {
  plans: [
    "맞춤 훈련 계획",
    "진단에서 만든 계획을 확인하고 연결된 훈련을 함께 마무리해요.",
  ],
  members: ["회원·권한", "권한 변경과 차단은 기존 로그인 세션을 끊습니다."],
  problems: [
    "문제은행",
    "목록 정보 편집과 검토 보류를 관리해요. 채점 패키지는 원래 검증 절차를 유지합니다.",
  ],
  training: [
    "회원 훈련",
    "진행 중인 훈련을 확인하고 사유를 남겨 마무리할 수 있어요.",
  ],
  jobs: [
    "작업 관리",
    "기존 재시도·취소 절차를 이용합니다. 채점 작업은 러너의 복구 절차를 따릅니다.",
  ],
  audit: ["감사 이력", "운영 변경과 관리자 재인증 기록을 확인해요."],
};
export function Records({ kind, currentUser }) {
  const [data, setData] = useState(null),
    [page, setPage] = useState(0),
    [size, setSize] = useState(25),
    [query, setQuery] = useState(""),
    [input, setInput] = useState(""),
    [error, setError] = useState(""),
    [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [action, setAction] = useState(null),
    [reason, setReason] = useState("");
  const sequence = useRef(0);
  async function load() {
    const seq = ++sequence.current;
    setBusy(true);
    setError("");
    try {
      const result = await api(
        "/api/admin/lists/" +
          kind +
          "?" +
          new URLSearchParams({ query, page, size }),
      );
      if (seq === sequence.current) setData(result);
    } catch (e) {
      if (seq === sequence.current) setError(e.message);
    } finally {
      if (seq === sequence.current) setBusy(false);
    }
  }
  useEffect(() => {
    setData(null);
    load();
    return () => {
      sequence.current++;
    };
  }, [kind, page, size, query]);
  function begin(title, path, body = null, method = "POST") {
    setAction({ title, path, body, method });
    setReason("");
    setError("");
    setMessage("");
  }
  async function apply(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await write(action.path, { ...action.body, reason }, action.method);
      setAction(null);
      setMessage("변경을 반영하고 감사 기록을 남겼어요.");
      await load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  function row(item) {
    const id = encodeURIComponent(item.id),
      user = encodeURIComponent(item.username || "");
    if (kind === "members")
      return (
        <>
          <th>
            {item.username}
            <small>{item.nickname}</small>
          </th>
          <td>
            {item.bootstrap
              ? "운영 복구 관리자"
              : item.admin_role === "ADMIN"
                ? "관리자"
                : "회원"}
          </td>
          <td>{item.blocked ? "차단" : "정상"}</td>
          <td>{item.sessions}</td>
          <td>{date(item.created_at)}</td>
          <td>
            <div className="control-row-actions">
              {!item.bootstrap && item.username !== currentUser && (
                <>
                  <button
                    className="secondary"
                    onClick={() =>
                      begin(
                        "회원 권한 변경",
                        "/api/admin/members/" + user + "/access",
                        {
                          role:
                            item.admin_role === "ADMIN" ? "MEMBER" : "ADMIN",
                          blocked: item.blocked,
                          revision: item.admin_revision,
                        },
                        "PUT",
                      )
                    }
                  >
                    {item.admin_role === "ADMIN"
                      ? "관리자 해제"
                      : "관리자 지정"}
                  </button>
                  <button
                    className="secondary"
                    onClick={() =>
                      begin(
                        item.blocked ? "회원 차단 해제" : "회원 차단",
                        "/api/admin/members/" + user + "/access",
                        {
                          role: item.admin_role,
                          blocked: !item.blocked,
                          revision: item.admin_revision,
                        },
                        "PUT",
                      )
                    }
                  >
                    {item.blocked ? "차단 해제" : "차단"}
                  </button>
                </>
              )}
              <button
                className="secondary"
                onClick={() =>
                  begin(
                    "모든 로그인 세션 종료",
                    "/api/admin/members/" + user + "/revoke",
                  )
                }
              >
                세션 종료
              </button>
            </div>
          </td>
        </>
      );
    if (kind === "problems")
      return (
        <>
          <th>
            {item.catalog_title || item.id}
            <small>
              {item.id}
              {item.owner ? " · " + item.owner : ""}
            </small>
          </th>
          <td>{item.catalog_category || "미분류"}</td>
          <td>
            {item.review_hold
              ? "검토 보류"
              : item.ready
                ? "준비 완료"
                : "검증 중"}
            {item.review_reason && <small>{item.review_reason}</small>}
          </td>
          <td>
            {item.diagnostic_only
              ? "진단 전용"
              : item.owner_id
                ? item.shared
                  ? "공개"
                  : "개인"
                : "기본 문제"}
          </td>
          <td>
            <div className="control-row-actions">
              <button
                className="secondary"
                onClick={() =>
                  begin(
                    "문제 목록 정보 편집",
                    "/api/admin/problems/" + id,
                    {
                      title: item.catalog_title || item.id,
                      category: item.catalog_category || "미분류",
                      tags: parseTags(item.catalog_tags),
                      shared: item.shared,
                      thinking: item.layer
                        ? {
                            layer: item.layer,
                            insight: item.insight,
                            implementation: item.implementation,
                            edgeCases: item.edge_cases,
                            rationale: item.rationale,
                          }
                        : null,
                      revision: item.admin_revision,
                    },
                    "PUT",
                  )
                }
              >
                편집
              </button>
              {item.ready && !item.review_hold && (
                <button
                  className="secondary"
                  onClick={() =>
                    begin(
                      "문제 검토 보류",
                      "/api/admin/problems/" + id + "/hold",
                    )
                  }
                >
                  검토 보류
                </button>
              )}
              <button
                className="secondary"
                onClick={() =>
                  begin("언어별 제한 확인", null, {
                    limits: item.time_limits_json,
                    resources: item.resources,
                  })
                }
              >
                제한 확인
              </button>
              {!item.diagnostic_only && (
                <button
                  className="secondary"
                  onClick={() =>
                    begin(
                      "언어별 제한 편집",
                      "/api/admin/problems/" + id + "/limits",
                      {
                        languages: item.resources,
                        revision: item.admin_revision,
                      },
                      "PUT",
                    )
                  }
                >
                  제한 편집
                </button>
              )}
            </div>
          </td>
        </>
      );
    if (kind === "plans")
      return (
        <>
          <th>{item.username}</th>
          <td>{item.id}</td>
          <td>{item.steps}</td>
          <td>{item.ended_at ? "마무리" : "학습 중"}</td>
          <td>
            {!item.ended_at && (
              <button
                className="secondary"
                onClick={() =>
                  begin("맞춤 계획 마무리", "/api/admin/plans/" + id + "/end")
                }
              >
                마무리
              </button>
            )}
          </td>
        </>
      );
    if (kind === "training")
      return (
        <>
          <th>{item.username}</th>
          <td>
            {item.problem_version}
            <small>{item.goal}</small>
          </td>
          <td>{status(item.status)}</td>
          <td>{date(item.started_at)}</td>
          <td>
            {item.status === "ACTIVE" ? (
              <button
                className="secondary"
                onClick={() =>
                  begin("훈련 마무리", "/api/admin/training/" + id + "/end")
                }
              >
                마무리
              </button>
            ) : (
              "—"
            )}
          </td>
        </>
      );
    if (kind === "jobs")
      return (
        <>
          <th>
            {
              {
                JUDGE: "채점",
                GENERATION: "문제 생성",
                HYBRID: "하이브리드 생성",
                RULE: "규칙 생성",
                AI: "AI 분석",
                EXPORT: "외부 업로드",
              }[item.kind]
            }
            <small>{item.id}</small>
          </th>
          <td>{item.username}</td>
          <td>
            {status(item.status)}
            {item.error_code && <small>{item.error_code}</small>}
          </td>
          <td>{date(item.created_at)}</td>
          <td>
            {(item.kind === "AI" &&
              item.retryable !== false &&
              ["FAILED", "UNKNOWN"].includes(item.status)) ||
            (item.kind === "EXPORT" &&
              ["FAILED", "RETRY"].includes(item.status)) ? (
              <button
                className="secondary"
                onClick={() =>
                  begin(
                    "작업 재시도",
                    "/api/admin/jobs/" + item.kind + "/" + id + "/retry",
                  )
                }
              >
                재시도
              </button>
            ) : (item.kind === "HYBRID" &&
                ![
                  "FAILED",
                  "CANCELLED",
                  "PUBLISHED",
                  "DEADLINE_EXCEEDED",
                  "READY",
                ].includes(item.status)) ||
              (item.kind === "RULE" &&
                [
                  "QUEUED",
                  "AUTHORING",
                  "AUTHORED",
                  "ORACLE",
                  "QUALIFYING",
                ].includes(item.status)) ? (
              <button
                className="secondary"
                onClick={() =>
                  begin(
                    "생성 작업 취소",
                    "/api/admin/jobs/" + item.kind + "/" + id + "/cancel",
                  )
                }
              >
                취소
              </button>
            ) : (
              "—"
            )}
          </td>
        </>
      );
    return (
      <>
        <td>{date(item.created_at)}</td>
        <th>{item.actor}</th>
        <td>
          {item.action}
          <small>{item.target}</small>
        </td>
        <td>{item.reason}</td>
        <td>
          <details>
            <summary>보기</summary>
            <pre className="control-json">
              {JSON.stringify(
                {
                  before: parseJSON(item.before_json),
                  after: parseJSON(item.after_json),
                },
                null,
                2,
              )}
            </pre>
          </details>
        </td>
      </>
    );
  }
  return (
    <>
      <div className="control-heading">
        <div>
          <h1>{headings[kind][0]}</h1>
          <p>{headings[kind][1]}</p>
        </div>
        <button className="secondary" disabled={busy} onClick={load}>
          새로고침
        </button>
      </div>
      <form
        className="control-toolbar"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setQuery(input.trim());
        }}
      >
        <label className="control-search">
          검색
          <input
            value={input}
            maxLength={100}
            placeholder={
              kind === "members" || kind === "training"
                ? "회원 아이디·이름"
                : kind === "problems"
                  ? "문제 ID·제목"
                  : "회원·작업 상태"
            }
            onChange={(e) => setInput(e.target.value)}
          />
        </label>
        <button className="secondary">검색</button>
        <label>
          표시 개수
          <SelectControl
            value={size}
            onChange={(e) => {
              setSize(Number(e.target.value));
              setPage(0);
            }}
          >
            {[10, 25, 50].map((n) => (
              <option key={n} value={n}>
                {n}개
              </option>
            ))}
          </SelectControl>
        </label>
      </form>
      <Feedback error={!action ? error : ""} message={message} />
      <div className="control-table-wrap" aria-busy={busy}>
        <table className="control-table">
          <thead>
            <tr>
              {columns[kind].map((title) => (
                <th key={title}>{title}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data?.items.map((item, i) => (
              <tr key={item.id || i}>{row(item)}</tr>
            ))}
          </tbody>
        </table>
        {!data ? (
          <p className="control-empty">
            {busy ? "목록을 불러오고 있어요." : "목록을 불러오지 못했어요."}
          </p>
        ) : (
          !data.items.length && (
            <p className="control-empty">조회 결과가 없어요.</p>
          )
        )}
      </div>
      <div className="control-pagination">
        <span>
          {data?.total ?? 0}개 · {page + 1} /{" "}
          {Math.max(1, Math.ceil((data?.total || 0) / size))} 페이지
        </span>
        <button
          className="secondary"
          disabled={busy || page === 0}
          onClick={() => setPage(page - 1)}
        >
          이전
        </button>
        <button
          className="secondary"
          disabled={busy || !data || (page + 1) * size >= data.total}
          onClick={() => setPage(page + 1)}
        >
          다음
        </button>
      </div>
      <Modal
        open={!!action}
        title={action?.title || "변경 확인"}
        wide={!!action?.body?.limits}
        onClose={() => {
          if (!busy) setAction(null);
        }}
      >
        {action && !action.path ? (
          <pre className="control-json">
            {JSON.stringify(
              action.body.limits
                ? parseJSON(action.body.limits)
                : action.body.resources,
              null,
              2,
            )}
          </pre>
        ) : (
          action && (
            <form className="control-form" onSubmit={apply}>
              {action.body?.title !== undefined && (
                <>
                  <label>
                    제목
                    <input
                      value={action.body.title}
                      required
                      maxLength={120}
                      onChange={(e) =>
                        setAction({
                          ...action,
                          body: { ...action.body, title: e.target.value },
                        })
                      }
                    />
                  </label>
                  <label>
                    분야
                    <input
                      value={action.body.category}
                      required
                      maxLength={80}
                      onChange={(e) =>
                        setAction({
                          ...action,
                          body: { ...action.body, category: e.target.value },
                        })
                      }
                    />
                  </label>
                  <label>
                    태그 · 쉼표로 구분
                    <input
                      value={action.body.tags.join(", ")}
                      maxLength={250}
                      onChange={(e) =>
                        setAction({
                          ...action,
                          body: {
                            ...action.body,
                            tags: e.target.value
                              .split(",")
                              .map((t) => t.trim()),
                          },
                        })
                      }
                    />
                  </label>
                  <label className="control-check">
                    <input
                      type="checkbox"
                      checked={action.body.shared}
                      onChange={(e) =>
                        setAction({
                          ...action,
                          body: { ...action.body, shared: e.target.checked },
                        })
                      }
                    />
                    다른 회원에게 공개
                  </label>
                  <label className="control-check">
                    <input
                      type="checkbox"
                      checked={!!action.body.thinking}
                      onChange={(e) =>
                        setAction({
                          ...action,
                          body: {
                            ...action.body,
                            thinking: e.target.checked
                              ? {
                                  layer: 1,
                                  insight: 1,
                                  implementation: 1,
                                  edgeCases: 1,
                                  rationale: "",
                                }
                              : null,
                          },
                        })
                      }
                    />
                    겹 난도 지정 · 끄면 기존 난도를 유지
                  </label>
                  {action.body.thinking && (
                    <>
                      <div className="control-form-columns">
                        {[
                          ["layer", "겹", 9],
                          ["insight", "발상", 5],
                          ["implementation", "구현", 5],
                          ["edgeCases", "예외 처리", 5],
                        ].map(([key, label, max]) => (
                          <label key={key}>
                            {label}
                            <input
                              type="number"
                              required
                              min={1}
                              max={max}
                              value={action.body.thinking[key]}
                              onChange={(e) =>
                                setAction({
                                  ...action,
                                  body: {
                                    ...action.body,
                                    thinking: {
                                      ...action.body.thinking,
                                      [key]: Number(e.target.value),
                                    },
                                  },
                                })
                              }
                            />
                          </label>
                        ))}
                      </div>
                      <label>
                        난도 근거
                        <textarea
                          required
                          maxLength={300}
                          rows={2}
                          value={action.body.thinking.rationale}
                          onChange={(e) =>
                            setAction({
                              ...action,
                              body: {
                                ...action.body,
                                thinking: {
                                  ...action.body.thinking,
                                  rationale: e.target.value,
                                },
                              },
                            })
                          }
                        />
                      </label>
                    </>
                  )}
                </>
              )}
              {action.body?.languages && (
                <>
                  <p>
                    신규 제출부터 적용됩니다. 운영자 조정으로 기록하며 측정 완료
                    값으로 표시하지 않습니다. 기존 제출·진단 스냅샷은
                    유지됩니다.
                  </p>
                  {Object.entries(action.body.languages).map(
                    ([language, r]) => (
                      <fieldset className="control-stage" key={language}>
                        <legend>{language}</legend>
                        {[
                          ["wallSeconds", "전체 실행 제한 · 초"],
                          ["cpuSeconds", "CPU 제한 · 초"],
                          ["memoryMb", "메모리 · MB"],
                        ].map(([key, label]) => (
                          <label key={key}>
                            {label}
                            <input
                              type="number"
                              required
                              min={key === "memoryMb" ? 32 : 0.1}
                              max={key === "memoryMb" ? 4096 : 180}
                              step={key === "memoryMb" ? 1 : 0.001}
                              value={r[key]}
                              onChange={(e) =>
                                setAction({
                                  ...action,
                                  body: {
                                    ...action.body,
                                    languages: {
                                      ...action.body.languages,
                                      [language]: {
                                        ...r,
                                        [key]: Number(e.target.value),
                                      },
                                    },
                                  },
                                })
                              }
                            />
                          </label>
                        ))}
                      </fieldset>
                    ),
                  )}
                </>
              )}
              {action.body?.role && (
                <p>
                  {action.body.role === "ADMIN"
                    ? "관리자로 지정합니다."
                    : "회원 권한으로 변경합니다."}{" "}
                  {action.body.blocked ? "접속을 차단합니다." : ""} 기존 로그인
                  세션은 종료됩니다.
                </p>
              )}
              {action.title === "작업 재시도" && (
                <p>
                  AI 작업 재시도는 API 비용이 추가될 수 있어요. 결과 미확정
                  작업은 원래 호출 상태부터 확인해 주세요.
                </p>
              )}
              {action.title === "문제 검토 보류" && (
                <p>
                  새 제출·훈련·자동 생성 매핑에서 제외됩니다. 다시 공개하려면
                  검증 절차를 거쳐야 해요.
                </p>
              )}
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
                {busy ? "반영 중…" : "확인·반영"}
              </button>
            </form>
          )
        )}
      </Modal>
    </>
  );
}
