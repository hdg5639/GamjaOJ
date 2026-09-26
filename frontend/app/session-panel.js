'use client';

import { useEffect, useState } from 'react';
import AiFeedback from './ai-feedback';

const summary = item => `정식 제출 ${item.submissions}회 · 정답 ${item.accepted}회 · 처리 중 ${item.pending}개`;
export default function SessionPanel({ user, problem, sessions, onChange, activity, api }) {
  const active = sessions.find(item => item.status === 'ACTIVE');
  const [goal, setGoal] = useState(user.trainingGoal || '');
  const [note, setNote] = useState('');
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState(null);
  const [entry, setEntry] = useState(null);
  const storageKey = `gamjaoj-session-request-${user.id}`;
  useEffect(() => {
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey));
      if (saved && ['start','end'].includes(saved.kind) && typeof saved.key === 'string' && saved.body) setPending(saved);
    } catch { /* In-memory retry remains available. */ }
  }, [user.id]);
  useEffect(() => {
    let stopped = false;
    async function refresh() {
      try {
        const items = await api('/api/training-sessions');
        const updated = detail ? await api(`/api/training-sessions/${detail.session.id}`) : null;
        const updatedEntry = entry && entry.status !== 'FINISHED'
          ? await api(`/api/${entry.input !== null ? 'runs' : 'submissions'}/${entry.id}`) : null;
        if (!stopped) { onChange(items); if (updated) setDetail(updated); if (updatedEntry) setEntry(updatedEntry); }
      } catch (e) { if (!stopped) setError(e.message); }
    }
    if (activity) refresh();
    window.addEventListener('focus', refresh);
    const needsPolling = sessions.some(item => item.pending > 0) || detail?.session.pending > 0 || (entry && entry.status !== 'FINISHED');
    const timer = needsPolling ? setInterval(refresh, 2500) : null;
    return () => { stopped = true; if (timer) clearInterval(timer); window.removeEventListener('focus', refresh); };
  }, [activity, detail?.session.id, sessions.some(item => item.pending > 0), detail?.session.pending, entry?.id, entry?.status]);

  useEffect(()=>{
    const show=async event=>{
      if(typeof event.detail!=='string')return;
      await open(event.detail);
      requestAnimationFrame(()=>document.getElementById('training-detail-heading')?.focus());
    };
    window.addEventListener('gamjaoj-training-open',show);
    return()=>window.removeEventListener('gamjaoj-training-open',show);
  },[user.id]);

  async function execute(attempt) {
    setBusy(true); setError(''); setPending(attempt);
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep the exact request in memory. */ }
    try {
      const result = await api(attempt.kind === 'start' ? '/api/training-sessions' : `/api/training-sessions/${attempt.key}/end`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key }, body: JSON.stringify(attempt.body),
      });
      setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Accepted request is reflected below. */ }
      setNote(''); onChange(await api('/api/training-sessions'));
      setDetail(await api(`/api/training-sessions/${result.id}`)); setEntry(null);
      window.dispatchEvent(new Event('gamjaoj-training-changed'));
    } catch (e) {
      setError(e.message);
      if (e.status && e.status < 500) {
        setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Memory cleared. */ }
        try { onChange(await api('/api/training-sessions')); } catch { /* Original error remains visible. */ }
      }
    } finally { setBusy(false); }
  }
  async function open(id) {
    try { setDetail(await api(`/api/training-sessions/${id}`)); setEntry(null); }
    catch (e) { setError(e.message); }
  }
  async function openEntry(item) {
    try { setEntry(await api(`/api/${item.kind === 'RUN' ? 'runs' : 'submissions'}/${item.id}`)); }
    catch (e) { setError(e.message); }
  }
  return <section className="training-panel editor-card" aria-label="훈련 세션">
    <h3>{active ? '진행 중인 훈련' : '훈련 기록 묶기'}</h3>
    {active?.problemHeld&&<p className="notice">문제 검토 중 · 기존 훈련을 마칠 수 있지만 새 작업과 분석은 보류됩니다.</p>}
    {active ? <>
      <p>{active.problemVersion} · 목표: {active.goal || '자유 연습'}</p>
      <p className="muted">{summary(active)}</p>
      <label>마무리 메모<textarea value={note} onChange={event => setNote(event.target.value)} maxLength={2000} rows={3} disabled={busy || !!pending} /></label>
      <button className="secondary" disabled={busy || !!pending} onClick={() => execute({kind:'end', key:active.id, body:{note}})}>훈련 마치기</button>
      <p className="draft-help">이미 접수한 채점은 종료 후에도 마저 처리돼요. 탭을 열어 둔 시간을 실제 학습 시간으로 평가하지 않아요.</p>
    </> : <>
      <p className="muted">시작하면 지금 문제의 정식 제출을 한 기록으로 모아요. 시작하지 않고 자유롭게 풀어도 괜찮아요.</p>
      <label>이번 훈련 목표<input value={goal} onChange={event => setGoal(event.target.value)} maxLength={120} disabled={busy || !!pending} /></label>
      <button className="secondary" disabled={busy || !!pending || !problem || problem.problemHeld} onClick={() => execute({kind:'start',key:crypto.randomUUID(),body:{problemVersion:problem.version,goal}})}>훈련 시작</button>
    </>}
    {pending && <button className="secondary" disabled={busy} onClick={() => execute(pending)}>같은 훈련 요청 다시 확인</button>}
    {error && <p role="alert" className="notice error">{error}</p>}
    <details><summary>내 훈련 기록 ({sessions.length})</summary>
      {sessions.map(item => <p key={item.id}><button className="secondary" onClick={() => open(item.id)}>
        {item.status === 'ACTIVE' ? '진행 중' : '종료'} · {item.problemVersion} · {new Date(item.startedAt).toLocaleString('ko-KR')}
      </button></p>)}
    </details>
    {detail && <article className="training-detail">
      <h4 id="training-detail-heading" tabIndex={-1}>{detail.session.status === 'ACTIVE' ? '진행 중인 기록' : '종료한 훈련 기록'}</h4>
      {detail.session.problemHeld&&<p className="notice">문제 검토 중 · 학습 판단 근거에서 제외된 기록입니다.</p>}
      <p>{detail.session.goal || '자유 연습'}</p><p>{summary(detail.session)}</p>
      <p>시작: {new Date(detail.session.startedAt).toLocaleString('ko-KR')}
        {detail.session.endedAt && ` · 종료: ${new Date(detail.session.endedAt).toLocaleString('ko-KR')}`}</p>
      {detail.session.note && <pre aria-label="저장된 마무리 메모">{detail.session.note}</pre>}
      <p className="draft-help">최근 작업 최대 50개를 표시해요. 훈련 종료 후 마지막 정식 제출의 분석을 요청하며, API 비활성·예산 부족 시 보류해요. 코드를 열어 개인 피드백을 확인할 수 있어요.</p>
      {detail.entries.map(item => <p key={item.id}><button className="secondary" onClick={() => openEntry(item)}>
        {item.kind === 'RUN' ? '직접 실행' : '정식 제출'} · {item.verdict || (item.status === 'RUNNING' ? '처리 중' : '대기')} · {new Date(item.createdAt).toLocaleString('ko-KR')}
      </button></p>)}
      {entry && <div><h4>당시 코드와 결과 · {entry.verdict || entry.status}</h4><pre aria-label="훈련에 저장된 코드">{entry.source}</pre>
        {entry.input !== null && <><h4>실행 입력</h4><pre>{entry.input || '(빈 입력)'}</pre><h4>표준 출력</h4><pre>{entry.stdout || '(출력 없음)'}</pre></>}
        {entry.outputTruncated && <p>출력이 길어 일부만 표시했어요.</p>}
        {entry.stderr && <><h4>표준 오류</h4><pre>{entry.stderr}</pre></>}
        {entry.compileMessage && <pre>{entry.compileMessage}</pre>}
        {entry.input===null&&<AiFeedback key={entry.id} submission={entry} api={api} />}
      </div>}
    </article>}
  </section>;
}
