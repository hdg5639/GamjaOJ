'use client';

import { useEffect, useRef, useState } from 'react';

const names = { OK: '실행 완료', CE: '컴파일 오류', RE: '실행 오류', TLE: '시간 초과',
  OLE: '출력 초과', MLE: '메모리 초과', IE: '실행 시스템 오류' };
const status = item => names[item.verdict] || (item.status === 'RUNNING' ? '실행 중' : '실행 대기');

export default function RunPanel({ user, source, problem, api, sessionId, onActivity }) {
  const [input, setInput] = useState('');
  const [history, setHistory] = useState([]);
  const [selected, setSelected] = useState(null);
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);
  const inputVersion = useRef(null);
  const storageKey = `gamjaoj-pending-run-${user.id}`;

  useEffect(() => {
    let stopped = false;
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey));
      if (saved && typeof saved.input === 'string' && typeof saved.source === 'string'
          && typeof saved.problemVersion === 'string' && typeof saved.key === 'string') {
        setPending(saved); setInput(saved.input); inputVersion.current = saved.problemVersion;
      }
    } catch { /* A failed request remains retryable in memory. */ }
    api('/api/runs').then(items => {
      if (!stopped) { setHistory(items); setReady(true); }
    }).catch(e => { if (!stopped) setError(e.message); });
    return () => { stopped = true; };
  }, [user.id]);

  useEffect(() => {
    if (problem && !pending && inputVersion.current !== problem.version) {
      inputVersion.current = problem.version;
      setInput(problem.sampleInput || '');
    }
  }, [problem?.version, pending]);

  useEffect(() => {
    if (!history.some(item => item.status !== 'FINISHED')) return;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const items = await api('/api/runs');
        const detail = selected && items.some(item => item.id === selected.id && item.status !== selected.status)
          ? await api(`/api/runs/${selected.id}`) : null;
        if (!stopped) { setHistory(items); if (detail) setSelected(detail); }
      } catch (e) { if (!stopped) setError(e.message); }
    }, 2500);
    return () => { stopped = true; clearInterval(timer); };
  }, [history, selected?.id, selected?.status]);

  async function run(event) {
    event.preventDefault();
    if (!pending && (new TextEncoder().encode(input).length > 16384 || new TextEncoder().encode(source).length > 65536)) {
      setError('입력은 16 KiB, 코드는 64 KiB 이내로 작성해 주세요.'); return;
    }
    if (!pending && !source.trim()) { setError('먼저 Main.java 코드를 작성해 주세요.'); return; }
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
    const key = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
    const attempt = pending || { key, problemVersion: problem.version, source, input, sessionId };
    setPending(attempt); setBusy(true); setError('');
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep in memory. */ }
    try {
      const result = await api('/api/runs', { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key },
        body: JSON.stringify({ problemVersion: attempt.problemVersion, source: attempt.source, input: attempt.input, sessionId: attempt.sessionId || null }) });
      setPending(null);
      try { sessionStorage.removeItem(storageKey); } catch { /* Keep the accepted result visible. */ }
      onActivity?.();
      setSelected(result); setHistory(items => [result, ...items.filter(item => item.id !== result.id)].slice(0, 50));
    } catch (e) {
      setError(e.message);
      if ((e.status && e.status < 500) || (e.status === 503 && e.message.includes('코드 채점을 준비'))) {
        setPending(null);
        try { sessionStorage.removeItem(storageKey); } catch { /* In-memory recovery remains available. */ }
      }
    } finally { setBusy(false); }
  }

  async function open(id) {
    try { setSelected(await api(`/api/runs/${id}`)); }
    catch (e) { setError(e.message); }
  }

  return <section className="custom-runs" aria-label="직접 입력 실행">
    <h3>직접 입력으로 실행</h3>
    <p className="muted">위 편집기의 코드를 실행해요. 정답 비교 없이 출력만 확인하며, 정식 제출 기록에는 포함하지 않아요.</p>
    <form className="editor-card" onSubmit={run}>
      <label htmlFor="custom-input">직접 입력</label>
      <textarea id="custom-input" value={input} onChange={event => setInput(event.target.value)} rows={4}
        maxLength={16384} spellCheck="false" disabled={busy} placeholder={problem?.sampleInput || '입력 없이도 실행할 수 있어요.'} />
      <button type="button" className="secondary" disabled={busy} onClick={() => setInput(problem?.sampleInput || '')}>예제 입력 넣기</button>
      <p className="draft-help">입력 최대 16 KiB · 출력은 최대 16 KiB까지 표시해요.</p>
      {pending && <p className="notice">이전 실행의 코드와 입력으로 접수 여부를 다시 확인해요.</p>}
      <button className="primary" disabled={busy || !ready || !problem || (!problem.submissionsEnabled && !pending)}>
        {busy ? '실행 접수 중…' : pending ? '같은 실행 다시 확인' : '직접 실행'}</button>
    </form>
    {error && <p role="alert" className="notice error">{error}</p>}
    <div className="history"><h4>내 직접 실행 기록</h4>
      {!history.length && <p className="muted">아직 직접 실행한 코드가 없어요.</p>}
      <ul>{history.map(item => <li key={item.id}><button onClick={() => open(item.id)}>
        <span>{item.problemVersion}<small>{new Date(item.createdAt).toLocaleString('ko-KR')}</small></span>
        <strong>{status(item)}</strong>
      </button></li>)}</ul>
      {selected && <article className="run-detail submission-detail">
        <h4>{status(selected)}</h4>
        <span className="version">{selected.runnerPolicy?.startsWith('java21') ? 'Java 21 · 이전 실행' : 'Java 8'}</span>
        <h4>실행한 입력</h4><pre aria-label="실행한 입력">{selected.input || '(빈 입력)'}</pre>
        {selected.status === 'FINISHED' && <>
          <h4>표준 출력</h4><pre aria-label="실행 표준 출력">{selected.stdout || '(출력 없음)'}</pre>
          {selected.outputTruncated && <p className="notice">출력이 길어 일부만 표시했어요.</p>}
          {selected.stderr && <><h4>표준 오류</h4><pre>{selected.stderr}</pre></>}
          {selected.compileMessage && <pre className="compiler-message">{selected.compileMessage}</pre>}
          {selected.verdict === 'IE' && <p className="notice">시스템 문제로 실행을 마치지 못했어요. 풀이 실패로 기록하지 않습니다.</p>}
        </>}
        <details><summary>실행한 코드 확인</summary><pre aria-label="직접 실행한 코드">{selected.source}</pre></details>
      </article>}
    </div>
  </section>;
}
