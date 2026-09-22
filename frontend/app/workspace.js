'use client';

import { useEffect, useRef, useState } from 'react';
import RunPanel from './run-panel';
import SessionPanel from './session-panel';

const starter = `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        long a = input.nextLong();
        long b = input.nextLong();
        // 두 정수의 합을 출력해 보세요.
    }
}
`;
const verdicts = { AC: '정답', WA: '오답', CE: '컴파일 오류', RE: '실행 오류', TLE: '시간 초과',
  MLE: '메모리 초과', OLE: '출력 초과', IE: '채점 시스템 오류' };
const label = item => item.verdict ? `${item.verdict} · ${verdicts[item.verdict]}` : item.status === 'RUNNING' ? '채점 중' : '채점 대기';

export default function Workspace({ user, api }) {
  const [problems, setProblems] = useState([]);
  const [screen, setScreen] = useState('practice');
  const [tool, setTool] = useState('run');
  const [sessions, setSessions] = useState([]);
  const [activity, setActivity] = useState(0);
  const activeSession = sessions.find(item => item.status === 'ACTIVE');
  const [version, setVersion] = useState('');
  const [source, setSource] = useState(starter);
  const [history, setHistory] = useState([]);
  const [selected, setSelected] = useState(null);
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [draftStatus, setDraftStatus] = useState('');
  const storageKey = `gamjaoj-pending-${user.id}`;
  const live = useRef(true);
  const problem = problems.find(item => item.version === version);

  useEffect(() => {
    live.current = true;
    let restoredPending = null;
    try {
      const stored = JSON.parse(sessionStorage.getItem(storageKey));
      if (stored && typeof stored.source === 'string' && typeof stored.problemVersion === 'string'
          && typeof stored.key === 'string') { restoredPending = stored; setPending(stored); }
    } catch { /* A request can still be retried in this tab if browser storage is unavailable. */ }
    Promise.all([api('/api/problems'), api('/api/submissions'), api('/api/training-sessions')]).then(([items, submissions, training]) => {
      if (!live.current) return;
      const current = training.find(item => item.status === 'ACTIVE');
      setSessions(training);
      const initialVersion = current?.problemVersion || (items.some(item => item.version === restoredPending?.problemVersion)
        ? restoredPending.problemVersion : items[0]?.version || '');
      setProblems(items); setVersion(initialVersion); setHistory(submissions);
      restoreDraft(initialVersion, restoredPending?.problemVersion === initialVersion ? restoredPending.source : starter);
      setLoaded(true);
    }).catch(e => { if (live.current) setError(e.message); });
    return () => { live.current = false; };
  }, [user.id]);

  function updateSessions(items) {
    setSessions(items);
    const active = items.find(item => item.status === 'ACTIVE');
    if (active && active.problemVersion !== version) { setVersion(active.problemVersion); restoreDraft(active.problemVersion); }
  }

  function draftKey(problemVersion) {
    return `gamjaoj-draft-v1-${user.id}-${encodeURIComponent(problemVersion)}`;
  }

  function restoreDraft(problemVersion, fallback = starter) {
    setSource(fallback);
    try {
      const draft = JSON.parse(localStorage.getItem(draftKey(problemVersion)));
      if (draft && typeof draft.source === 'string' && draft.source.length <= 65536) {
        setSource(draft.source); setDraftStatus('이 브라우저에 저장된 초안을 불러왔어요.');
      } else setDraftStatus('작성한 코드는 이 브라우저에 자동 저장돼요.');
    } catch {
      setDraftStatus('초안을 불러오지 못했어요. 필요한 코드는 파일로 보관해 주세요.');
    }
  }

  function editSource(value) {
    setSource(value);
    // Save in the edit event: an immediate reload or logout must not beat a debounce timer.
    try {
      localStorage.setItem(draftKey(version), JSON.stringify({ source: value }));
      setDraftStatus('이 브라우저에 초안을 저장했어요.');
    } catch {
      setDraftStatus('자동 저장하지 못했어요. Main.java 내려받기로 보관해 주세요.');
    }
  }

  async function importSource(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setError('');
    if (!file.name.toLowerCase().endsWith('.java') || file.size > 65536) {
      setError('64 KiB 이하의 UTF-8 .java 파일을 선택해 주세요.'); return;
    }
    setBusy(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      if (!live.current) return;
      editSource(text);
      setNotice('파일을 편집기에 불러왔어요. 채점하려면 코드 제출을 눌러 주세요.');
    } catch {
      if (live.current) setError('파일을 읽지 못했어요. UTF-8 인코딩인지 확인해 주세요.');
    } finally { if (live.current) setBusy(false); }
  }

  function downloadSource() {
    const url = URL.createObjectURL(new Blob([source], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = 'Main.java'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  useEffect(() => {
    if (!history.some(item => item.status !== 'FINISHED')) return;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const items = await api('/api/submissions');
        if (stopped || !live.current) return;
        let detail = null;
        if (selected && items.some(item => item.id === selected.id && item.status !== selected.status)) {
          detail = await api(`/api/submissions/${selected.id}`);
        }
        if (stopped || !live.current) return;
        setHistory(items);
        if (detail) setSelected(detail);
      } catch (e) { if (!stopped && live.current) setError(e.message); }
    }, 2500);
    return () => { stopped = true; clearInterval(timer); };
  }, [history, selected?.id, selected?.status]);

  async function submit(event) {
    event.preventDefault();
    if (new TextEncoder().encode(source).length > 65536 && !pending) {
      setError('코드는 UTF-8 기준 64 KiB 이내로 입력해 주세요.'); return;
    }
    // Internal HTTP is not a secure browser context; do not depend on crypto.randomUUID().
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
    const key = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
    const attempt = pending || { key, problemVersion: version, source, sessionId: activeSession?.id || null };
    setPending(attempt); setBusy(true); setError(''); setNotice('');
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep in memory. */ }
    try {
      const result = await api('/api/submissions', { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key },
        body: JSON.stringify({ problemVersion: attempt.problemVersion, source: attempt.source, sessionId: attempt.sessionId || null }) });
      if (!live.current) return;
      setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Browser storage disabled. */ }
      setTool('history');
      setSelected(result); setHistory(items => [result, ...items.filter(item => item.id !== result.id)].slice(0,50));
      setActivity(value => value + 1);
      setNotice('제출한 코드를 저장했어요. 채점 결과는 자동으로 갱신됩니다.');
    } catch (e) {
      if (!live.current) return;
      setError(e.message);
      if ((e.status && e.status < 500) || (e.status === 503 && e.message.includes('코드 채점을 준비'))) {
        setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Browser storage disabled. */ }
      }
    } finally { if (live.current) setBusy(false); }
  }

  async function open(id) {
    try { const detail = await api(`/api/submissions/${id}`); if (live.current) setSelected(detail); }
    catch (e) { if (live.current) setError(e.message); }
  }

  return <section className="workspace" aria-label="문제 풀이">
    <div className="workspace-heading"><div><h1 className="workspace-title">알고리즘 연습</h1><span className="muted">{activeSession ? `훈련 중 · ${activeSession.goal || '자유 연습'}` : '코드를 작성하고, 실행하고, 제출하세요.'}</span></div>
      <nav className="workspace-nav" aria-label="작업 화면"><button className={screen === 'practice' ? 'active' : ''} onClick={() => setScreen('practice')}>문제 풀기</button>
      <button className={screen === 'training' ? 'active' : ''} onClick={() => setScreen('training')}>훈련 기록</button></nav></div>
    <div hidden={screen !== 'training'}>{loaded && <SessionPanel user={user} problem={problem} sessions={sessions} onChange={updateSessions} activity={activity} api={api} />}</div>
    <div hidden={screen !== 'practice'}>
    {problem && <div className="practice-grid">
      <article className="problem-card">
        <span className="version">{problem.version}</span><h3>{problem.title}</h3><p>{problem.statement}</p>
        <h4>예제 입력</h4><pre>{problem.sampleInput}</pre><h4>예제 출력</h4><pre>{problem.sampleOutput}</pre>
        <p className="muted">클래스 이름은 Main으로 작성해 주세요. 제출한 코드는 기록에서 다시 확인할 수 있어요.</p>
      </article>
      <form className="editor-card" onSubmit={submit}>
        {problems.length > 1 && <label>풀이할 문제<select value={version} disabled={busy || !!pending || !!activeSession}
          onChange={event => { setVersion(event.target.value); restoreDraft(event.target.value); }}>
          {problems.map(item => <option key={item.version} value={item.version}>{item.title} · {item.version}</option>)}
        </select></label>}
        <div className="code-heading"><label htmlFor="source">Main.java</label><span className="language-badge">Java 8</span></div><textarea id="source" name="source" value={source}
          onChange={event => editSource(event.target.value)}
          onKeyDown={event => {
            if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
              event.preventDefault(); event.currentTarget.form.requestSubmit(); return;
            }
            if (event.key === 'Tab' && !event.shiftKey) {
              event.preventDefault(); const field = event.currentTarget;
              const start = field.selectionStart, end = field.selectionEnd;
              editSource(source.slice(0, start) + '    ' + source.slice(end));
              requestAnimationFrame(() => { field.selectionStart = field.selectionEnd = start + 4; });
            }
          }} rows={16} spellCheck="false" autoCapitalize="off"
          maxLength={65536} required={!pending} disabled={busy} />
        <p className="draft-status" aria-live="polite">{draftStatus}</p>
        <span className="draft-help">Tab 들여쓰기 · Ctrl/⌘ + Enter 제출</span>
        <details className="file-tools"><summary>파일 불러오기 / 내려받기</summary><div className="editor-tools">
          <label className="file-import">Java 파일 불러오기<input type="file" accept=".java" disabled={busy} onChange={importSource} /></label>
          <button type="button" className="secondary" onClick={downloadSource}>Main.java 내려받기</button>
        </div>
        <p className="draft-help">초안은 계정·문제별로 이 브라우저에만 남아요. 다른 기기로 이동할 때는 파일을 내려받아 주세요. 공용 기기에서는 사용 후 사이트 데이터를 지워 주세요.</p></details>
        {!problem.submissionsEnabled && <p className="notice">코드 채점을 준비하고 있어요. 지금은 문제를 읽고 풀이를 작성할 수 있어요.</p>}
        {pending && <p className="notice">이전 제출의 접수 여부를 다시 확인합니다. 그때 보낸 코드로 확인해요.</p>}
        <div className="editor-actions"><button type="button" className="secondary" onClick={() => {
          setTool('run'); requestAnimationFrame(() => document.getElementById('custom-input')?.focus());
        }}>입력 테스트</button><button className="primary" disabled={busy || (!problem.submissionsEnabled && !pending)}>
          {busy ? '제출 확인 중…' : pending ? '같은 제출 다시 확인' : '코드 제출'}</button></div>
      </form>
    </div>}
    {!loaded && !error && <p role="status">문제와 내 제출 기록을 불러오고 있어요…</p>}
    {error && <p role="alert" className="notice error">{error}</p>}
    {notice && <p role="status" className="notice success">{notice}</p>}
    <div className="result-dock"><nav className="result-tabs" aria-label="실행과 제출 결과">
      <button className={tool === 'run' ? 'active' : ''} onClick={() => setTool('run')}>실행 테스트</button>
      <button className={tool === 'history' ? 'active' : ''} onClick={() => setTool('history')}>제출 기록</button>
    </nav><div hidden={tool !== 'run'}><RunPanel key={user.id} user={user} source={source} problem={problem} api={api} sessionId={activeSession?.id || null} onActivity={() => setActivity(value => value + 1)} /></div>
    <div hidden={tool !== 'history'}>
    <div className="history"><h3>내 제출 기록</h3>
      {!history.length && <p className="muted">아직 제출한 코드가 없어요.</p>}
      {history.length > 0 && <ul>{history.map(item => <li key={item.id}><button onClick={() => open(item.id)}>
        <span><strong>{item.problemVersion}</strong><small>{new Date(item.createdAt).toLocaleString('ko-KR')}</small></span>
        <span className={`verdict ${item.verdict || ''}`}>{label(item)}</span>
      </button></li>)}</ul>}
      {selected && <article className="submission-detail"><div className="workspace-heading"><h4>제출한 코드</h4>
        <strong>{label(selected)}</strong></div>
        <span className="version">{selected.runnerPolicy?.startsWith('java21') ? 'Java 21 · 이전 제출' : 'Java 8'}</span>
        {selected.verdict === 'IE' && <p className="notice">채점 시스템 문제로 결과를 확인하지 못했어요. 풀이 실패로 기록하지 않습니다.</p>}
        {selected.compileMessage && <pre className="compiler-message">{selected.compileMessage}</pre>}
        <pre aria-label="저장된 제출 코드">{selected.source}</pre>
        <small className="muted">제출 ID: {selected.id}</small>
      </article>}
    </div></div></div></div>
  </section>;
}
