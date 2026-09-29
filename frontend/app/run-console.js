'use client';

import { useEffect, useRef, useState } from 'react';
import { languageInfo, recordLanguageLabel, limitText } from './languages';
import { verdictText, verdictHelp, verdictNames } from './verdicts';

const tokens = text => (text || '').trim().split(/\s+/).filter(Boolean);
const bytes = text => new TextEncoder().encode(text).length;
const read = (key, fallback) => { try { const v = JSON.parse(localStorage.getItem(key)); return v ?? fallback; } catch { return fallback; } };
const write = (key, value) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* Kept in memory only. */ } };

function outcome(c) {
  const r = c.result;
  if (!r) return ['대기 중이에요.', undefined];
  if (r.status !== 'FINISHED') return ['실행 중이에요…', undefined];
  if (r.verdict !== 'OK') return [`${verdictText(r.verdict)}${verdictHelp[r.verdict] ? ' · ' + verdictHelp[r.verdict] : ''}`, false];
  if (c.output == null || c.output === '') return ['실행을 마쳤어요. 기댓값이 없어 비교하지 않았어요.', undefined];
  return tokens(r.stdout).join(' ') === tokens(c.output).join(' ') ? ['테스트를 통과하였습니다.', true] : ['실행한 결괏값이 기댓값과 다릅니다.', false];
}

/**
 * Coding-test style console under the editor, shared by practice and diagnostics.
 * '코드 실행' runs every public example and then the learner's own test cases one by one (sequential, so the
 * per-user limit of three unfinished jobs is never hit by the run itself) and compares each with its expected output.
 * Added cases are kept per problem in this browser. It fills the lower pane of the editor/console split.
 */
export default function RunConsole({ user, api, body, examples, scope, disabled, runRequest, casesRequest, onCaseCount, onActivity, submission, onShowRecords, children }) {
  const casesKey = `gamjaoj-test-cases-${user.id}-${scope}`;
  const [cases, setCases] = useState([]), [editing, setEditing] = useState(false);
  const [runs, setRuns] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const live = useRef(true), runRef = useRef(null), toasted = useRef(null);
  const [mode, setMode] = useState('runs'), [toast, setToast] = useState(null);
  // A fresh formal submission takes over the console and, once judged, pops a short summary.
  useEffect(() => { if (submission?.id) setMode('submission'); }, [submission?.id]);
  useEffect(() => {
    if (!submission || submission.status !== 'FINISHED' || toasted.current === submission.id) return;
    toasted.current = submission.id; setToast(submission);
    const timer = setTimeout(() => setToast(null), 6000);
    return () => clearTimeout(timer);
  }, [submission?.id, submission?.status]);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  useEffect(() => { const saved = read(casesKey, []); setCases(Array.isArray(saved) ? saved.filter(c => typeof c?.input === 'string') : []); setRuns(null); setError(''); }, [casesKey]);
  useEffect(() => { onCaseCount?.(cases.length); }, [cases.length]);
  useEffect(() => { if (casesRequest) setEditing(open => !open); }, [casesRequest]);
  useEffect(() => { if (runRequest) runRef.current?.(); }, [runRequest]);

  function changeCases(next) { setCases(next); write(casesKey, next); }

  async function run() {
    if (busy || disabled) return;
    if (!body.source.trim()) { setError(`먼저 ${languageInfo[body.language]?.file || '코드'}를 작성해 주세요.`); return; }
    if (bytes(body.source) > 65536) { setError('코드는 64 KiB 이내로 작성해 주세요.'); return; }
    const list = [...examples.map((e, i) => ({ label: `테스트 ${i + 1}`, input: e.input || '', output: e.output ?? '' })),
      ...cases.map((c, i) => ({ label: `추가 ${i + 1}`, input: c.input, output: c.output || '', custom: true }))];
    if (list.some(c => bytes(c.input) > 16384)) { setError('입력은 케이스마다 16 KiB 이내로 작성해 주세요.'); return; }
    setBusy(true); setError(''); setEditing(false); setMode('runs');
    const snapshot = { scope, source: body.source, cases: list };
    setRuns(snapshot);
    const update = (index, result) => live.current && setRuns(state => state && state.scope === scope
      ? { ...state, cases: state.cases.map((c, j) => j === index ? { ...c, result } : c) } : state);
    try {
      for (let index = 0; index < list.length; index++) {
        let result = await api('/api/runs', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
          body: JSON.stringify({ ...body, input: list[index].input }) });
        update(index, result); onActivity?.();
        while (result.status !== 'FINISHED' && live.current) {
          await new Promise(resolve => setTimeout(resolve, 900));
          result = await api(`/api/runs/${result.id}`); update(index, result);
        }
        if (result.verdict === 'CE' || !live.current) break;
      }
    } catch (e) { if (live.current) setError(e.message); }
    finally { if (live.current) setBusy(false); }
  }
  runRef.current = run;

  const shown = runs?.scope === scope ? runs : null;
  const results = shown ? shown.cases.map(outcome) : [];
  const finished = !!shown && shown.cases.every(c => c.result?.status === 'FINISHED' || (!c.result && shown.cases.some(x => x.result?.verdict === 'CE')));
  const judged = results.filter(r => r[1] !== undefined);
  const first = shown?.cases.find(c => c.result)?.result;
  const showing = mode === 'submission' && submission ? 'submission' : 'runs';
  return <section className="run-console" aria-label="실행 결과">
    {toast && <div className="submit-toast" role="status" data-verdict={toast.verdict}>
      <span>제출 결과 〉 <strong>{verdictText(toast.verdict)}</strong>{toast.tests?.length ? ` · ${toast.tests.filter(t => t.verdict === 'AC').length} / ${toast.verdict === 'AC' ? toast.tests.length : Math.max(toast.testCount || 0, toast.tests.length)}개 통과` : ''}</span>
      <button type="button" aria-label="제출 결과 알림 닫기" onClick={() => setToast(null)}>×</button></div>}
    <div className="console-head"><h3>{submission ? <span className="console-tabs">
      <button type="button" aria-pressed={showing === 'runs'} onClick={() => setMode('runs')}>실행 결과</button>
      <button type="button" aria-pressed={showing === 'submission'} onClick={() => setMode('submission')}>제출 결과</button></span> : '실행 결과'}</h3>
      <small>{finished && judged.length > 0 ? `${judged.filter(r => r[1]).length} / ${judged.length}개 통과` : first ? `${recordLanguageLabel(first)} · ${limitText(first.execution)}` : ''}</small></div>
    {editing && <div className="case-editor" role="group" aria-label="테스트 케이스 추가">
      <p className="case-editor-help">예제와 함께 실행할 테스트 케이스를 추가하세요. 기댓값을 비우면 출력만 보여 줘요. 추가한 케이스는 이 문제에 한해 이 브라우저에 저장돼요.</p>
      {cases.map((c, index) => <div className="case-row" key={index}>
        <label>추가 {index + 1} · 입력<textarea aria-label={`추가 ${index + 1} · 입력`} value={c.input} rows={3} spellCheck="false" maxLength={16384} disabled={busy}
          onChange={e => changeCases(cases.map((x, j) => j === index ? { ...x, input: e.target.value } : x))} /></label>
        <label>기댓값 (선택)<textarea aria-label={`추가 ${index + 1} · 기댓값`} value={c.output || ''} rows={3} spellCheck="false" maxLength={16384} disabled={busy}
          onChange={e => changeCases(cases.map((x, j) => j === index ? { ...x, output: e.target.value } : x))} /></label>
        <button type="button" className="secondary" disabled={busy} aria-label={`추가 ${index + 1} 삭제`} onClick={() => changeCases(cases.filter((_, j) => j !== index))}>삭제</button>
      </div>)}
      <div className="case-editor-actions">
        <button type="button" className="secondary" disabled={busy || cases.length >= 10} onClick={() => changeCases([...cases, { input: '', output: '' }])}>+ 케이스 추가</button>
        {examples[0] && <button type="button" className="secondary" disabled={busy || cases.length >= 10} onClick={() => changeCases([...cases, { input: examples[0].input || '', output: '' }])}>예제 1 입력으로 추가</button>}
        <button type="button" className="primary" onClick={() => setEditing(false)}>완료</button>
      </div>
    </div>}
    {error && <p role="alert" className="notice error">{error}</p>}
    <div className="console-body" aria-live="polite">
      {children}
      {showing === 'submission' && <article className="submission-view" data-verdict={submission.verdict || 'PENDING'}>
        <p className="submission-summary">정식 제출 〉 <strong>{submission.status === 'FINISHED' ? verdictText(submission.verdict) : '채점 중이에요…'}</strong>
          {submission.status === 'FINISHED' && verdictHelp[submission.verdict] && <span> · {verdictHelp[submission.verdict]}</span>}</p>
        {submission.compileMessage && <pre className="compiler-message">{submission.compileMessage}</pre>}
        <SubmitTests submission={submission} />
        {onShowRecords && <button type="button" className="secondary" onClick={onShowRecords}>제출 기록·피드백 보기</button>}
      </article>}
      {showing === 'runs' && !shown && <p className="muted">‘코드 실행’을 누르면 예제 {examples.length}개{cases.length ? `와 추가한 케이스 ${cases.length}개` : ''}를 차례로 실행하고 기댓값과 비교한 결과가 여기에 나와요. 실행은 제출 기록에 남지 않아요.</p>}
      {showing === 'runs' && shown && shown.source !== body.source && <p className="notice">현재 편집 중인 코드와 다른 실행의 결과예요.</p>}
      {showing === 'runs' && shown?.cases.map((c, index) => <article className="run-detail" key={index} data-passed={results[index][1] === undefined ? undefined : String(results[index][1])}>
        <dl className="console-case">
          <dt>{c.label}</dt><dd></dd>
          <dt>입력값</dt><dd><pre aria-label={`${c.label} 입력`}>{c.input || '(빈 입력)'}</pre></dd>
          {c.output !== '' && <><dt>기댓값</dt><dd><pre aria-label={`${c.label} 기댓값`}>{c.output}</pre></dd></>}
          {c.result?.status === 'FINISHED' && <><dt>출력</dt><dd><pre aria-label={`${c.label} 출력`}>{c.result.stdout || '(출력 없음)'}</pre></dd></>}
          <dt>실행 결과</dt><dd><strong className="console-outcome">{results[index][0]}</strong></dd>
        </dl>
        {c.result?.outputTruncated && <p className="notice">출력이 길어 일부만 표시했어요.</p>}
        {c.result?.compileMessage && <pre className="compiler-message">{c.result.compileMessage}</pre>}
        {c.result?.stderr && <><h4>표준 오류</h4><pre>{c.result.stderr}</pre></>}
        {c.result?.verdict === 'IE' && <p className="notice">시스템 문제로 실행을 마치지 못했어요. 풀이 실패로 기록하지 않습니다.</p>}
      </article>)}
    </div>
  </section>;
}

/** Programmers-style per-test summary of a formal submission: numbers and pass/fail only, never hidden inputs. */
export function SubmitTests({ submission }) {
  const tests = submission?.tests || [];
  if (submission?.status !== 'FINISHED' || !tests.length) return null;
  const total = submission.verdict === 'AC' ? tests.length : Math.max(submission.testCount || 0, tests.length);
  const rest = total - tests.length;
  return <ol className="submit-tests" aria-label="테스트별 채점 결과">
    {tests.map(t => <li key={t.number} data-pass={t.verdict === 'AC'}>테스트 {t.number} 〉 <strong>{t.verdict === 'AC' ? '통과' : '실패'}</strong>
      <span> ({t.verdict === 'AC' ? (t.wallMs != null ? `${t.wallMs}ms` : '통과') : verdictNames[t.verdict] || t.verdict})</span></li>)}
    {rest > 0 && <li className="skipped">테스트 {tests.length + 1}{rest > 1 ? `~${total}` : ''} 〉 앞선 실패로 채점하지 않았어요</li>}
  </ol>;
}

/** Compact examples: each input sits beside its output (stacked when the problem pane is narrow). */
export function Examples({ examples }) {
  const numbered = examples.length > 1;
  return <div className="examples">{examples.map((e, i) => <div className="example" key={i}>
    <div><h3>예제 입력{numbered ? ` ${i + 1}` : ''}</h3><pre>{e.input}</pre></div>
    <div><h3>예제 출력{numbered ? ` ${i + 1}` : ''}</h3><pre>{e.output}</pre></div>
    {e.explanation && <p className="example-note"><strong>예제{numbered ? ` ${i + 1}` : ''} 설명</strong> {e.explanation}</p>}
  </div>)}</div>;
}
