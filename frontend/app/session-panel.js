'use client';
import SelectControl from './select-control';

import { useEffect, useRef, useState } from 'react';
import Pager,{usePage} from './pager';
import AiFeedback from './ai-feedback';
import {verdictText} from './verdicts';
import ProblemId from './problem-id';
import Modal from './modal';
import {createPortal} from 'react-dom';

const summary = item => `정식 제출 ${item.submissions}회 · 정답 ${item.accepted}회 · 처리 중 ${item.pending}개`;
export default function SessionPanel({ user, problem, sessions, onChange, activity, api, problems=[], onOpen, onDiagnostic, locked=false, view="records", onRecords=()=>{}, controlsHost=null }) {
  const active = sessions.find(item => item.status === 'ACTIVE');
  const [goal, setGoal] = useState(user.trainingGoal || '');
  const [note, setNote] = useState('');
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState(null);
  const [entry, setEntry] = useState(null);
  const [filter,setFilter]=useState('ALL'),[query,setQuery]=useState('');
  const [target,setTarget]=useState(problem?.version||'');
  const [opening,setOpening]=useState(false),[controlsOpen,setControlsOpen]=useState(false),[detailOpen,setDetailOpen]=useState(false);
  const openRevision=useRef(0),executionLock=useRef(false);
  const title=version=>problems.find(item=>item.version===version)?.title||'목록에 없는 문제';
  const targetProblem=problems.find(item=>item.version===target);
  useEffect(()=>{setTarget(problem?.version||'');},[problem?.version]);
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
      onRecords();await open(event.detail);
      requestAnimationFrame(()=>document.getElementById('training-detail-heading')?.focus());
    };
    window.addEventListener('gamjaoj-training-open',show);
    return()=>window.removeEventListener('gamjaoj-training-open',show);
  },[user.id,onRecords]);

  async function execute(attempt) {
    if(executionLock.current)return;executionLock.current=true;
    setBusy(true); setError(''); setPending(attempt);
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep the exact request in memory. */ }
    try {
      const result = await api(attempt.kind === 'start' ? '/api/training-sessions' : `/api/training-sessions/${attempt.key}/end`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key }, body: JSON.stringify(attempt.body),
      });
      setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Accepted request is reflected below. */ }
      setControlsOpen(false);setNote(''); onChange(await api('/api/training-sessions'));
      setDetail(await api(`/api/training-sessions/${result.id}`)); setEntry(null);
      window.dispatchEvent(new Event('gamjaoj-training-changed'));
    } catch (e) {
      setError(e.message);
      if (e.status && e.status < 500) {
        setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Memory cleared. */ }
        try { onChange(await api('/api/training-sessions')); } catch { /* Original error remains visible. */ }
      }
    } finally { executionLock.current=false;setBusy(false); }
  }
  async function open(id) {
    const revision=++openRevision.current;setOpening(true);setError('');
    try { const value=await api(`/api/training-sessions/${id}`);if(revision!==openRevision.current)return;
      setDetail(value);setEntry(null);setDetailOpen(true);
      requestAnimationFrame(()=>{const heading=document.getElementById('training-detail-heading');heading?.scrollIntoView({block:'start'});heading?.focus({preventScroll:true});});
    } catch (e) { if(revision===openRevision.current)setError(e.message); }
    finally { if(revision===openRevision.current)setOpening(false); }
  }
  async function openEntry(item) {
    try { setEntry(await api(`/api/${item.kind === 'RUN' ? 'runs' : 'submissions'}/${item.id}`)); }
    catch (e) { setError(e.message); }
  }
  const filtered=sessions.filter(item=>(filter==='ALL'||item.status===filter)&&`${title(item.problemVersion)} ${item.goal||''} ${item.problemVersion}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const sessionPaging=usePage(filtered,10),entryPaging=usePage(detail?.entries||[],10);
  const panel=<section className={view==='records'?"training-panel editor-card":"training-session-tools"} aria-label="훈련 세션">
    <header className="training-section-heading">{!controlsHost&&<div><h2>{view==='records'?'내 훈련 기록':active?'진행 중인 훈련':'직접 정한 목표로 연습'}</h2><p className="muted">{view==='records'?'지난 실행이 아니라, 목표별로 묶은 정식 제출과 마무리 기록이에요.':active?`${title(active.problemVersion)} · ${active.goal||'자유 연습'}`:'진단 계획 외에도 문제와 목표를 직접 선택할 수 있어요.'}</p></div>}
      <div className="training-tool-actions">{active&&<button className="primary" disabled={locked||busy||!!pending||active.problemHeld} onClick={async()=>{try{await onOpen(active.problemVersion);}catch(e){setError(e.message);}}}>훈련 이어 풀기</button>}<button className="secondary" onClick={()=>setControlsOpen(true)}>{active?'훈련 마무리':'직접 훈련 시작'}</button></div></header>
    {!controlsOpen&&pending&&<button className="secondary" disabled={busy} onClick={()=>execute(pending)}>같은 훈련 요청 다시 확인</button>}
    {!controlsOpen&&error&&<p role="alert" className="notice error">{error}</p>}
    <Modal open={controlsOpen} title={active?'훈련 마무리':'직접 훈련 시작'} onClose={()=>setControlsOpen(false)} className="diagnostic-dialog"><div className="diagnostic-dialog-content">
    <section className="training-current" aria-label={active?'진행 중인 훈련':'새 훈련'}>
    <div className="training-section-heading"><div><h3>{active ? '진행 중인 훈련' : '새 훈련 시작'}</h3>
      {active&&<p className="training-problem-title">{title(active.problemVersion)}</p>}</div>
</div>
    {active?.problemHeld&&<p className="notice">문제 검토 중 · 기존 훈련을 마칠 수 있지만 새 작업과 분석은 보류됩니다.</p>}
    {active ? <>
      <p className="training-goal">목표 · {active.goal || '자유 연습'}</p>
      <p className="muted">{summary(active)}</p>
      <div className="training-finish">
      <label>마무리 메모<textarea value={note} onChange={event => setNote(event.target.value)} maxLength={2000} rows={3} disabled={busy || !!pending} /></label>
      <button className="secondary" disabled={busy || !!pending} onClick={() => execute({kind:'end', key:active.id, body:{note}})}>훈련 마치기</button>
      <p className="draft-help">이미 접수한 채점은 종료 후에도 마저 처리돼요. 탭을 열어 둔 시간을 실제 학습 시간으로 평가하지 않아요.</p>
      </div>
    </> : <>
      <p className="muted">선택한 문제의 제출을 한 기록으로 모아요. 목표를 비워 두면 자유 연습으로 기록합니다.</p>
      <div className="training-start-fields"><label>훈련할 문제<SelectControl aria-label="훈련할 문제" value={target} onChange={event=>setTarget(event.target.value)} disabled={busy||!!pending||locked}>
        {!target&&<option value="">문제를 선택해 주세요</option>}{problems.map(item=><option key={item.version} value={item.version} disabled={item.problemHeld}>{item.problemHeld?'[검토 중] ':''}{item.title}</option>)}
      </SelectControl></label>
      <label>이번 훈련 목표<input value={goal} onChange={event => setGoal(event.target.value)} maxLength={120} placeholder="예: 경계값을 먼저 확인하고 구현하기" disabled={busy || !!pending} /></label></div>
      {!problems.length&&<p className="draft-help">훈련할 수 있는 문제를 준비하고 있어요.</p>}
      <button className="primary" disabled={busy || !!pending || locked || !targetProblem || targetProblem.problemHeld} onClick={() => execute({kind:'start',key:crypto.randomUUID(),body:{problemVersion:target,goal}})}>훈련 시작</button>
    </>}
    </section>
    {pending && <button className="secondary" disabled={busy} onClick={() => execute(pending)}>같은 훈련 요청 다시 확인</button>}
    {error && <p role="alert" className="notice error">{error}</p>}
    </div></Modal>
    {view==='records'&&<section className="training-history" aria-label="내 훈련 기록">
      <div className="training-section-heading"><h3>내 훈련 기록 <span className="muted">최근 {sessions.length}개</span></h3><span className="muted">진행 중 {sessions.filter(item=>item.status==='ACTIVE').length} · 종료 {sessions.filter(item=>item.status!=='ACTIVE').length}</span></div>
      <p className="draft-help">최근 훈련 최대 20개에서 검색·필터합니다. 상세 기록에는 최근 정식 제출 최대 50개를 표시해요.</p>
      <div className="training-record-filters"><label><span className="sr-only">훈련 기록 검색</span><input type="search" value={query} placeholder="문제명 또는 훈련 목표 검색" onChange={event=>{setQuery(event.target.value);sessionPaging.setPage(0);}}/></label>
        <label><span className="sr-only">훈련 상태</span><SelectControl aria-label="훈련 상태" value={filter} onChange={event=>{setFilter(event.target.value);sessionPaging.setPage(0);}}><option value="ALL">전체 상태</option><option value="ACTIVE">진행 중</option><option value="ENDED">종료</option></SelectControl></label>
        <button className="secondary" disabled={!query&&filter==='ALL'} onClick={()=>{setQuery('');setFilter('ALL');sessionPaging.setPage(0);}}>초기화</button></div>
      {opening&&<p role="status" className="muted">훈련 상세 기록을 불러오는 중…</p>}
      {!sessions.length?<p className="training-empty">아직 훈련 기록이 없어요. 학습 계획에서 연습을 시작하거나 목표를 직접 정해 보세요.</p>:!filtered.length?<p className="training-empty">조건에 맞는 기록이 없어요. 검색어나 상태를 바꿔 보세요.</p>:<ul className="training-record-list">
      {sessionPaging.visible.map(item => <li key={item.id}><button className="training-record-row" aria-current={detail?.session.id===item.id?'true':undefined} onClick={() => open(item.id)}>
        <span className="training-record-main"><strong>{title(item.problemVersion)}</strong><span>{item.goal||'자유 연습'}</span><small>{new Date(item.startedAt).toLocaleString('ko-KR')} · <ProblemId version={item.problemVersion}/></small></span>
        <span className="training-record-meta"><strong>{item.status==='ACTIVE'?'진행 중':'종료'}{item.problemHeld?' · 검토 중':''}</strong><span>정식 제출 {item.submissions}회 · 정답 {item.accepted}회</span>{item.pending>0&&<span>처리 중 {item.pending}개</span>}<small>상세 기록 보기 →</small></span>
      </button></li>)}
      </ul>}
      <Pager paging={sessionPaging} label="훈련 기록 페이지"/>
    </section>}
    <Modal open={detailOpen} title="훈련 상세 기록" onClose={()=>{setDetailOpen(false);setEntry(null);}} className="diagnostic-dialog" wide><div className="diagnostic-dialog-content">
    {detail && <article className="training-detail">
      <div className="training-section-heading"><h3 id="training-detail-heading" tabIndex={-1}>{detail.session.status === 'ACTIVE' ? '진행 중인 기록' : '종료한 훈련 기록'}</h3><button className="secondary" onClick={()=>{openRevision.current++;setOpening(false);setDetailOpen(false);setDetail(null);setEntry(null);}}>상세 닫기</button></div>
      <p className="training-problem-title">{title(detail.session.problemVersion)}</p>
      {detail.session.problemHeld&&<p className="notice">문제 검토 중 · 학습 판단 근거에서 제외된 기록입니다.</p>}
      <p>{detail.session.goal || '자유 연습'}</p><p>{summary(detail.session)}</p>
      <p>시작: {new Date(detail.session.startedAt).toLocaleString('ko-KR')}
        {detail.session.endedAt && ` · 종료: ${new Date(detail.session.endedAt).toLocaleString('ko-KR')}`}</p>
      {detail.session.note && <pre aria-label="저장된 마무리 메모">{detail.session.note}</pre>}
      <p className="draft-help">최근 작업 최대 50개를 표시해요. 훈련 종료 후 마지막 정식 제출의 분석을 요청하며, API 비활성·예산 부족 시 보류해요. 코드를 열어 개인 피드백을 확인할 수 있어요.</p>
      {entryPaging.visible.map(item => <p key={item.id}><button className="secondary" onClick={() => openEntry(item)}>
        {item.kind === 'RUN' ? '직접 실행' : '정식 제출'} · {verdictText(item.verdict) || (item.status === 'RUNNING' ? '처리 중' : '대기')} · {new Date(item.createdAt).toLocaleString('ko-KR')}
      </button></p>)}
      <Pager paging={entryPaging} label="실행·제출 기록 페이지"/>
      {entry && <div><h4>당시 코드와 결과 · {verdictText(entry.verdict) || entry.status}</h4><pre aria-label="훈련에 저장된 코드">{entry.source}</pre>
        {entry.input !== null && <><h4>실행 입력</h4><pre>{entry.input || '(빈 입력)'}</pre><h4>표준 출력</h4><pre>{entry.stdout || '(출력 없음)'}</pre></>}
        {entry.outputTruncated && <p>출력이 길어 일부만 표시했어요.</p>}
        {entry.stderr && <><h4>표준 오류</h4><pre>{entry.stderr}</pre></>}
        {entry.compileMessage && <pre>{entry.compileMessage}</pre>}
        {entry.input===null&&<AiFeedback key={entry.id} submission={entry} api={api} />}
      </div>}
    </article>}
    </div></Modal>
  </section>;
  return controlsHost?createPortal(panel,controlsHost):panel;
}
