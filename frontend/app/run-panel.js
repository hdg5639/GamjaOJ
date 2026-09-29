'use client';

import { useEffect, useRef, useState } from 'react';
import {languageInfo,recordLanguageLabel,limitText} from './languages';
import {verdictText,verdictHelp} from './verdicts';

const status = item => item.verdict ? verdictText(item.verdict) : item.status === 'RUNNING' ? '실행 중' : '실행 대기';

const tokens=text=>(text||'').trim().split(/\s+/).filter(Boolean);
/** Coding-test style console under the editor: run with the sample and compare, or run custom input. */
export default function RunPanel({ user, source, language='JAVA', problem, api, sessionId, onActivity, inputRequest, runRequest }) {
  const [inputOpen,setInputOpen]=useState(false);
  const live=useRef(true);
  const [input, setInput] = useState('');
  const [selected, setSelected] = useState(null);
  const [pending, setPending] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);
  const inputVersion = useRef(null);
  const storageKey = `gamjaoj-pending-run-${user.id}`;

  useEffect(() => {
    let stopped = false;live.current=true;
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey));
      if (saved && typeof saved.input === 'string' && typeof saved.source === 'string'
          && typeof saved.problemVersion === 'string' && typeof saved.key === 'string') {
        setPending(saved); setInput(saved.input); inputVersion.current = saved.problemVersion;
      }
    } catch { /* A failed request remains retryable in memory. */ }
    try {const id=sessionStorage.getItem(`gamjaoj-active-run-${user.id}`);if(id)api(`/api/runs/${id}`).then(result=>{if(!stopped)setSelected(result);}).catch(()=>{try{sessionStorage.removeItem(`gamjaoj-active-run-${user.id}`);}catch{}});}catch{}
    setReady(true);
    return () => { stopped = true;live.current=false; };
  }, [user.id]);

  useEffect(() => {
    if (problem && !pending && inputVersion.current !== problem.version) {
      inputVersion.current = problem.version;
      setInput(problem.sampleInput || '');
    }
  }, [problem?.version, pending]);

  useEffect(() => {
    if(!selected)return;
    const activeKey=`gamjaoj-active-run-${user.id}`;
    try{if(selected.status==='FINISHED')sessionStorage.removeItem(activeKey);else sessionStorage.setItem(activeKey,selected.id);}catch{}
    if(selected.status==='FINISHED')return;
    let stopped=false;
    const timer=setInterval(async()=>{
      try{const detail=await api(`/api/runs/${selected.id}`);if(!stopped)setSelected(detail);}
      catch(e){if(!stopped)setError(e.message);}
    },2500);
    return()=>{stopped=true;clearInterval(timer);};
  },[selected?.id,selected?.status,user.id]);

  useEffect(()=>{if(inputRequest){setInputOpen(open=>!open);requestAnimationFrame(()=>document.getElementById('custom-input')?.focus());}},[inputRequest]);
  useEffect(()=>{if(runRequest)run();},[runRequest]);
  async function run(event) {
    event?.preventDefault();
    if(busy)return;
    if (!pending && (new TextEncoder().encode(input).length > 16384 || new TextEncoder().encode(source).length > 65536)) {
      setError('입력은 16 KiB, 코드는 64 KiB 이내로 작성해 주세요.'); return;
    }
    if (!pending && !source.trim()) { setError(`먼저 ${languageInfo[language].file} 코드를 작성해 주세요.`); return; }
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
    const key = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
    const attempt = pending || { key, problemVersion: problem.version, source, language, input, sessionId };
    setPending(attempt); setBusy(true); setError('');
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep in memory. */ }
    try {
      const result = await api('/api/runs', { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key },
        body: JSON.stringify({ problemVersion: attempt.problemVersion, source: attempt.source, language:attempt.language||'JAVA', input: attempt.input, sessionId: attempt.sessionId || null }) });
      if(!live.current)return;
      setInputOpen(false);
      setPending(null);
      try { sessionStorage.removeItem(storageKey); } catch { /* Keep the accepted result visible. */ }
      onActivity?.();
      setSelected(result);
    } catch (e) {
      if(!live.current)return;
      setError(e.message);
      if ((e.status && e.status < 500) || (e.status === 503 && e.message.includes('코드 채점을 준비'))) {
        setPending(null);
        try { sessionStorage.removeItem(storageKey); } catch { /* In-memory recovery remains available. */ }
      }
    } finally { if(live.current)setBusy(false); }
  }

  const current=selected&&selected.problemVersion===problem?.version?selected:null;
  const sample=current&&problem&&current.input===(problem.sampleInput||'');
  const passed=sample&&current.status==='FINISHED'&&current.verdict==='OK'&&tokens(current.stdout).join(' ')===tokens(problem.sampleOutput).join(' ');
  const outcome=!current?null:current.status!=='FINISHED'?'실행 중이에요…':current.verdict!=='OK'?`${status(current)}${verdictHelp[current.verdict]?' · '+verdictHelp[current.verdict]:''}`
    :!sample?'실행을 마쳤어요. 직접 넣은 입력이라 기댓값과 비교하지 않았어요.':passed?'테스트를 통과하였습니다.':'실행한 결괏값이 기댓값과 다릅니다.';
  return <section className="run-console" aria-label="실행 결과">
    <div className="console-head"><h3>실행 결과</h3>{current&&<small>{recordLanguageLabel(current)} · {new Date(current.createdAt).toLocaleTimeString('ko-KR')}</small>}</div>
    {inputOpen&&<form className="console-input" onSubmit={run}>
      <label htmlFor="custom-input">직접 넣을 입력</label>
      <textarea id="custom-input" value={input} onChange={event => setInput(event.target.value)} rows={4}
        maxLength={16384} spellCheck="false" disabled={busy} placeholder={problem?.sampleInput || '입력 없이도 실행할 수 있어요.'} />
      <div className="console-input-actions"><button type="button" className="secondary" disabled={busy} onClick={() => setInput(problem?.sampleInput || '')}>예제 입력으로 되돌리기</button>
        <span className="draft-help">입력 최대 16 KiB · 출력은 16 KiB까지 표시 · 정식 제출에는 포함되지 않아요.</span></div>
    </form>}
    {pending && <p className="notice">이전 실행의 코드와 입력으로 접수 여부를 다시 확인해요. <button type="button" className="secondary" disabled={busy} onClick={()=>run()}>같은 실행 다시 확인</button></p>}
    {error && <p role="alert" className="notice error">{error}</p>}
    <div className="console-body" aria-live="polite">
      {!ready&&!error&&<p role="status" className="muted">실행을 준비하고 있어요…</p>}
      {ready&&!current&&<p className="muted">아래 ‘코드 실행’을 누르면 예제 입력으로 실행하고 기댓값과 비교한 결과가 여기에 나와요. 다른 입력은 ‘입력 직접 넣기’로 넣을 수 있어요.</p>}
      {current&&<article className="run-detail" data-passed={current.status==='FINISHED'?String(!!passed):undefined}>
        {(current.problemHeld||problem?.problemHeld)&&<p className="notice">문제 검토 중 · 기존 실행 기록입니다.</p>}
        {current.source!==source&&<p className="notice">현재 편집 중인 코드와 다른 실행의 결과예요.</p>}
        <dl className="console-case">
          <dt>{sample?'테스트 1':'직접 입력'}</dt><dd></dd>
          <dt>입력값</dt><dd><pre aria-label="실행한 입력">{current.input || '(빈 입력)'}</pre></dd>
          {sample&&<><dt>기댓값</dt><dd><pre aria-label="기댓값">{problem.sampleOutput}</pre></dd></>}
          {current.status==='FINISHED'&&<><dt>출력</dt><dd><pre aria-label="실행 표준 출력">{current.stdout || '(출력 없음)'}</pre></dd></>}
          <dt>실행 결과</dt><dd><strong id="run-heading" tabIndex={-1} className="console-outcome">{outcome}</strong></dd>
        </dl>
        {current.status==='FINISHED'&&<>
          {current.outputTruncated && <p className="notice">출력이 길어 일부만 표시했어요.</p>}
          {current.stderr && <><h4>표준 오류</h4><pre>{current.stderr}</pre></>}
          {current.compileMessage && <pre className="compiler-message">{current.compileMessage}</pre>}
          {current.verdict === 'IE' && <p className="notice">시스템 문제로 실행을 마치지 못했어요. 풀이 실패로 기록하지 않습니다.</p>}
        </>}
        <span className="version">{limitText(current.execution)}</span>
      </article>}
    </div>
  </section>;
}
