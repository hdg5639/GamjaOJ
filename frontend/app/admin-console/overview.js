"use client";
import { useEffect, useState } from "react";
import { api } from "./api";
import { Feedback, date, status } from "./shared";
export function Overview() {
  const [data, setData] = useState(null),
    [budget, setBudget] = useState(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function load() {
    setBusy(true);
    setError("");
    try {
      const [overview, b] = await Promise.all([
        api("/api/admin/overview"),
        api("/api/admin/budget"),
      ]);
      setData(overview);
      setBudget(b);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    load();
  }, []);
  return (
    <>
      <div className="control-heading">
        <div>
          <h1>운영 현황</h1>
          <p>
            {data
              ? date(data.measuredAt) + " 기준"
              : "서버에 기록된 운영 상태를 확인해요."}
          </p>
        </div>
        <button className="secondary" onClick={load} disabled={busy}>
          {busy ? "조회 중…" : "새로고침"}
        </button>
      </div>
      <Feedback error={error} />
      {data && (
        <div className="control-board control-glass">
          <dl className="control-totals">
            {[
              ["등록 회원", data.members, "명"],
              ["준비된 문제", data.problems, "개"],
              ["채점 대기", data.judgeQueue.QUEUED || 0, "건"],
              ["채점 처리 중", data.judgeQueue.RUNNING || 0, "건"],
            ].map(([label, value, unit]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>
                  {value.toLocaleString()}
                  <small>{unit}</small>
                </dd>
              </div>
            ))}
          </dl>
          <div className="control-queues">
            {[
              ["제출·실행", data.judgeQueue],
              ["문제 생성", data.generationJobs],
              ["AI 분석", data.aiTasks],
            ].map(([title, values]) => (
              <section className="control-section" key={title}>
                <h2>{title}</h2>
                <table>
                  <thead>
                    <tr>
                      <th>상태</th>
                      <th>건수</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(values).map(([key, count]) => (
                      <tr key={key}>
                        <th>{status(key)}</th>
                        <td>{count.toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            ))}
          </div>
          {budget && (
            <p className="control-budget">
              AI 예산 · 지출 ${Number(budget.spentUsd || 0).toFixed(2)} · 예약 $
              {Number(budget.reservedUsd || 0).toFixed(2)} · 월 한도 $
              {Number(budget.limitUsd || 0).toFixed(2)} ·{" "}
              {budget.enabled ? "활성" : "비활성"}
            </p>
          )}
        </div>
      )}
    </>
  );
}
