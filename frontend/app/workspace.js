'use client';

import { useEffect, useRef, useState } from 'react';
import {languageInfo,starters,recordLanguage,recordLanguageLabel,limitText} from './languages';
import RunConsole, { SubmitTests, Examples } from './run-console';
import LimitChips from './limit-chips';
import {useEditorSizing,ResizeHandle,splitScale} from './editor-sizing';
import DiagnosticPanel from './diagnostic-panel';
import RecordHistory from './record-history';
import MyPage from './my-page';
import ResetCode from './reset-code';
import SessionPanel from './session-panel';
import ProblemCatalog from './problem-catalog';
import NavIcon from './nav-icon';
import AiFeedback from './ai-feedback';
import FollowupPanel from './followup-panel';
import ProblemTeaching from './problem-teaching';
import AiOperations, { AiBudget } from './ai-operations';
import dynamic from 'next/dynamic';
import {verdictText,verdictHelp} from './verdicts';

const CodeEditor = dynamic(() => import('./code-editor'), { ssr: false,
  loading: () => <div id="source" role="status">편집기를 불러오고 있어요…</div>,
});

const starter = `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        // 문제의 입력 형식에 맞춰 읽고 결과를 출력하세요.
    }
}
`;
const label = item => item.verdict ? verdictText(item.verdict) : item.status === 'RUNNING' ? '채점 중' : '채점 대기';

export default function Workspace({ user, api, sidebarCollapsed, onToggleSidebar }) {
  const [size,changeSize]=useEditorSizing(user.id,'practice');
  const [problems, setProblems] = useState([]);
  const [screen, updateScreen] = useState('home');
  function setScreen(next) { updateScreen(next); window.history.pushState(null,'','#'+next); if(next==='home')requestAnimationFrame(()=>document.querySelector('.catalog-view')?.scrollTo(0,0)); }
  useEffect(()=>{
    const sync=()=>{const value=window.location.hash.slice(1)||'home';if(['home','practice','catalog','diagnostic','training','generation','mypage'].includes(value))updateScreen(value);};
    sync();window.addEventListener('popstate',sync);return()=>window.removeEventListener('popstate',sync);
  },[]);
  const [generationMode,setGenerationMode]=useState('tags'),[ruleDraft,setRuleDraft]=useState(null);
  const [tool, setTool] = useState('history'),[runRequest,setRunRequest]=useState(0);
  const [resultsOpen, setResultsOpen] = useState(false);
  const [resultSize, setResultSize] = useState(360);
  const [casesRequest,setCasesRequest]=useState(0),[caseCount,setCaseCount]=useState(0);
  const [historyOpen,setHistoryOpen]=useState(false);
  const [inspected,setInspected]=useState(null);
  const panelRevision=useRef(0),selectionRevision=useRef(0),catalogRevision=useRef(0);
  const [mobilePane, setMobilePane] = useState('code');
  const [sessions, setSessions] = useState([]);
  const [activity, setActivity] = useState(0);
  const activeSession = sessions.find(item => item.status === 'ACTIVE');
  const [version, setVersion] = useState('');
  const [language,setLanguage]=useState('JAVA');
  const lang=languageInfo[language];
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
  const languageKey = `gamjaoj-language-${user.id}`;
  const selectionKey = `gamjaoj-selected-problem-${user.id}`;
  const live = useRef(true);
  const problem = problems.find(item => item.version === version);
  const currentSession = activeSession?.problemVersion === version ? activeSession : null;

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
      let remembered='';try{remembered=localStorage.getItem(selectionKey)||'';}catch{/* Storage may be unavailable. */}
      const initialVersion = items.some(item=>item.version===restoredPending?.problemVersion)?restoredPending.problemVersion:
        items.some(item=>item.version===remembered&&!item.problemHeld)?remembered:
        current?.problemVersion || items.find(item=>!item.problemHeld)?.version || items[0]?.version || '';
      let preferred='JAVA';try{preferred=localStorage.getItem(languageKey)||'JAVA';}catch{}
      const chosen=restoredPending ? (restoredPending.language||'JAVA') : preferred;
      const initialLanguage=languageInfo[chosen] && (items.find(p=>p.version===initialVersion)?.languages||[languageInfo.JAVA]).some(l=>l.id===chosen)?chosen:'JAVA';
      setLanguage(initialLanguage);
      setProblems(items); setVersion(initialVersion); setHistory(submissions);
      restoreDraft(initialVersion, restoredPending?.problemVersion === initialVersion ? restoredPending.source : starters[initialLanguage],initialLanguage);
      if(restoredPending)setScreen('practice');
      setLoaded(true);
    }).catch(e => { if (live.current) setError(e.message); });
    return () => { live.current = false; };
  }, [user.id]);

  useEffect(()=>{
    let stopped=false;
    async function refreshProblems(){const revision=++catalogRevision.current;try{
      const items=await api('/api/problems');if(stopped||revision!==catalogRevision.current)return;
      setProblems(items);
      const held=new Set(items.filter(item=>item.problemHeld).map(item=>item.version));
      setSelected(value=>value?{...value,problemHeld:held.has(value.problemVersion)}:value);
      setHistory(values=>values.map(value=>({...value,problemHeld:held.has(value.problemVersion)})));
    }catch(e){if(!stopped&&revision===catalogRevision.current)setError(e.message);}}
    if(loaded&&['home','catalog'].includes(screen))refreshProblems();
    window.addEventListener('focus',refreshProblems);
    window.addEventListener('gamjaoj-problems-changed',refreshProblems);
    return()=>{stopped=true;window.removeEventListener('focus',refreshProblems);window.removeEventListener('gamjaoj-problems-changed',refreshProblems);};
  },[user.id,screen,loaded]);

  useEffect(()=>{
    const created=()=>setScreen('training');
    window.addEventListener('gamjaoj-followup-created',created);
    return()=>window.removeEventListener('gamjaoj-followup-created',created);
  },[]);
  async function openTraining(problemVersion){
    if(busy||pending)throw new Error('진행 중인 제출을 먼저 확인해 주세요.');
    const [items,training]=await Promise.all([api('/api/problems'),api('/api/training-sessions')]);
    setProblems(items);setSessions(training);chooseProblem(problemVersion);
  }

  function updateSessions(items) {
    setSessions(items);
    // Refresh session metadata without replacing the problem/draft the user is browsing.
  }

  function chooseProblem(nextVersion) {
    if (busy || pending) return;
    try{localStorage.setItem(selectionKey,nextVersion);}catch{/* Draft restoration still works without selection persistence. */}
    if (nextVersion !== version) {
      setVersion(nextVersion);
      restoreDraft(nextVersion);
    }
    setScreen('practice');
    setInspected(null);
    panelRevision.current++;setResultsOpen(false);
    setMobilePane('problem');
    requestAnimationFrame(() => document.getElementById('problem-title')?.focus());
  }

  function draftKey(problemVersion, chosen=language) {
    return `gamjaoj-draft-v1-${user.id}-${encodeURIComponent(problemVersion)}${chosen==='JAVA'?'':'-'+chosen}`;
  }

  function restoreDraft(problemVersion, fallback = starters[language], chosen=language) {
    setSource(fallback);
    try {
      const draft = JSON.parse(localStorage.getItem(draftKey(problemVersion,chosen)));
      if (draft && typeof draft.source === 'string' && draft.source.length <= 65536) {
        setSource(draft.source); setDraftStatus('이 브라우저에 저장된 초안을 불러왔어요.');
      } else setDraftStatus('작성한 코드는 이 브라우저에 자동 저장돼요.');
    } catch {
      setDraftStatus('초안을 불러오지 못했어요. 필요한 코드는 파일로 보관해 주세요.');
    }
  }

  function changeLanguage(next) {
    if(busy||pending)return;
    setLanguage(next);setInspected(null);restoreDraft(version,starters[next],next);
    try{localStorage.setItem(languageKey,next);}catch{}
  }
  function editSource(value) {
    setSource(value);
    // Save in the edit event: an immediate reload or logout must not beat a debounce timer.
    try {
      localStorage.setItem(draftKey(version), JSON.stringify({ source: value }));
      setDraftStatus('이 브라우저에 초안을 저장했어요.');
    } catch {
      setDraftStatus('자동 저장하지 못했어요. 코드 내려받기로 보관해 주세요.');
    }
  }

  async function importSource(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setError('');
    if (!file.name.toLowerCase().endsWith(lang.extension) || file.size > 65536) {
      setError(`64 KiB 이하의 UTF-8 ${lang.extension} 파일을 선택해 주세요.`); return;
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
    link.href = url; link.download = lang.file; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  useEffect(() => {
    if (!history.some(item => item.status !== 'FINISHED')) return;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const items = await api(`/api/submissions?problemVersion=${encodeURIComponent(version)}`);
        if (stopped || !live.current) return;
        let detail = null;
        if (selected && items.some(item => item.id === selected.id && item.status !== selected.status)) {
          detail = await api(`/api/submissions/${selected.id}`);
        }
        if (stopped || !live.current) return;
        const completed=items.some(item=>item.status==='FINISHED'&&history.some(previous=>previous.id===item.id&&previous.status!=='FINISHED'));
        setHistory(items);
        if(completed)window.dispatchEvent(new Event('gamjaoj-problems-changed'));
        if (detail) setSelected(detail);
      } catch (e) { if (!stopped && live.current) setError(e.message); }
    }, 2500);
    return () => { stopped = true; clearInterval(timer); };
  }, [history, selected?.id, selected?.status,version]);

  useEffect(()=>{
    if(!version)return;
    let stopped=false;selectionRevision.current++;setSelected(null);setHistory([]);setHistoryOpen(false);setInspected(null);
    api(`/api/submissions?problemVersion=${encodeURIComponent(version)}`).then(items=>{if(!stopped)setHistory(items);}).catch(e=>{if(!stopped)setError(e.message);});
    return()=>{stopped=true;};
  },[version,user.id]);

  function showTool(next){panelRevision.current++;setTool(next);setResultsOpen(true);setMobilePane('results');}
  function closeResults(){panelRevision.current++;setResultsOpen(false);setMobilePane('code');requestAnimationFrame(()=>document.getElementById('tool-'+tool)?.focus());}
  function viewCode(item){setInspected(item);setMobilePane('code');requestAnimationFrame(()=>document.getElementById('snapshot-heading')?.focus());}
  function resizePanel(event){
    event.currentTarget.setPointerCapture(event.pointerId);
    const start=event.clientX,initial=resultSize;
    event.currentTarget.dataset.dragStart=start;event.currentTarget.dataset.dragWidth=initial;event.currentTarget.dataset.direction=window.innerWidth>=1440?-1:1;
  }
  function dragPanel(event){
    if(!event.currentTarget.hasPointerCapture(event.pointerId))return;
    const delta=(event.clientX-Number(event.currentTarget.dataset.dragStart))*Number(event.currentTarget.dataset.direction);
    setResultSize(Math.max(300,Math.min(520,Number(event.currentTarget.dataset.dragWidth)+delta)));
  }
  async function submit(event) {
    event.preventDefault();
    if (inspected || busy || (!problem?.submissionsEnabled && !pending)) return;
    if (!pending && !source.trim()) { setError(`먼저 ${lang.file} 코드를 작성해 주세요.`); return; }
    if (new TextEncoder().encode(source).length > 65536 && !pending) {
      setError('코드는 UTF-8 기준 64 KiB 이내로 입력해 주세요.'); return;
    }
    // Internal HTTP is not a secure browser context; do not depend on crypto.randomUUID().
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(value => value.toString(16).padStart(2, '0')).join('');
    const key = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
    const intent=panelRevision.current;selectionRevision.current++;
    const attempt = pending || { key, problemVersion: version, source, language, sessionId: currentSession?.id || null };
    setPending(attempt); setBusy(true); setError(''); setNotice('');
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt)); } catch { /* Keep in memory. */ }
    try {
      const result = await api('/api/submissions', { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': attempt.key },
        body: JSON.stringify({ problemVersion: attempt.problemVersion, source: attempt.source, language:attempt.language||'JAVA', sessionId: attempt.sessionId || null }) });
      if (!live.current) return;
      setPending(null); try { sessionStorage.removeItem(storageKey); } catch { /* Browser storage disabled. */ }
      setHistoryOpen(false);
      setSelected(result); setHistory(items => [result, ...items.filter(item => item.id !== result.id)].slice(0,50));
      window.dispatchEvent(new Event('gamjaoj-problems-changed'));
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
    const revision=++selectionRevision.current;
    try { const detail = await api(`/api/submissions/${id}`); if (live.current&&revision===selectionRevision.current) {setSelected(detail);setHistoryOpen(false);requestAnimationFrame(()=>document.getElementById('submission-heading')?.focus());} }
    catch (e) { if (live.current) setError(e.message); }
  }

  return <section className="workspace" data-screen={screen} data-sidebar-collapsed={sidebarCollapsed} aria-label="문제 풀이">
    <aside id="learning-navigation" className="app-navigation" aria-label="학습 내비게이션">
      <div className="sidebar-heading"><span className="nav-section-label">LEARN & PRACTICE</span><button className="sidebar-toggle" type="button" aria-label={sidebarCollapsed?'사이드바 펼치기':'사이드바 접기'} title={sidebarCollapsed?'사이드바 펼치기':'사이드바 접기'} aria-expanded={!sidebarCollapsed} aria-controls="learning-navigation" onClick={onToggleSidebar}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/><path d={sidebarCollapsed?'m13 9 3 3-3 3':'m16 9-3 3 3 3'}/></svg></button></div>
      <nav className="workspace-nav" aria-label="작업 화면">{[
        ['home','문제 탐색'],['practice','문제 풀기'],['diagnostic','선택 진단'],['generation','내 문제 생성'],['training','훈련 기록'],['mypage','마이페이지']
      ].map(([id,title])=><button key={id} title={title} aria-pressed={screen===id||(id==='home'&&screen==='catalog')} className={screen===id||(id==='home'&&screen==='catalog')?'active':''} onClick={()=>setScreen(id)}><NavIcon name={id}/><span>{title}</span></button>)}</nav>
      <div className="navigation-note"><img className="brand-symbol" src="/gamjaoj-favicon.svg" alt="" width="36" height="36"/><p>한 문제씩,<br/>내 것으로.</p><small>GamjaOJ · CODE & LEARN</small></div>
    </aside>
    <div className="workspace-heading"><div><span className="page-kicker">{['home','catalog'].includes(screen)?'PROBLEM LIBRARY':screen==='practice'?'WORKSPACE':'MY LEARNING'}</span><h1 className="workspace-title">{{home:'문제 탐색',catalog:'문제 탐색',practice:'문제 풀기',diagnostic:'선택 진단',generation:'내 문제 생성',training:'훈련 기록',mypage:'마이페이지'}[screen]}</h1></div>
      {screen==='practice'&&<span className="muted">{currentSession ? `훈련 중 · ${currentSession.goal || '자유 연습'}` : activeSession ? `자유 풀이 · ${activeSession.problemVersion} 훈련은 유지 중` : `${lang.label} · ${lang.file}`}</span>}
      {['home','catalog'].includes(screen)&&<button className="primary" onClick={()=>setScreen('generation')}>+ 문제 만들기</button>}
    </div>
    {screen==='mypage'&&<div className="training-view"><MyPage api={api} user={user} problems={problems} onChoose={chooseProblem} onDiagnostic={()=>setScreen('diagnostic')}/></div>}
    <div className="diagnostic-view" hidden={screen !== 'diagnostic'}>{screen === 'diagnostic' && <DiagnosticPanel user={user} api={api} onOpen={openTraining} onGeneration={()=>{setGenerationMode('request');setScreen('generation');}} onRuleDraft={draft=>{setRuleDraft({...(typeof draft==='string'?{text:draft}:draft),key:crypto.randomUUID()});setGenerationMode('hybrid');setScreen('generation');}} onPractice={()=>setScreen('practice')} />}</div>
    <div className="catalog-view" hidden={!['home','catalog'].includes(screen)}><ProblemCatalog home={['home','catalog'].includes(screen)} onNavigate={setScreen} api={api} onChanged={value=>{setProblems(items=>items.map(p=>p.version===value.version?value:p));window.dispatchEvent(new Event('gamjaoj-problems-changed'));}} problems={problems} loaded={loaded} error={error}
      selectedVersion={version} locked={busy || !!pending} onChoose={chooseProblem} /></div>
    <div className="training-view" hidden={screen !== 'training'}>{screen === 'training' && <AiBudget api={api} />}{loaded&&<FollowupPanel api={api} onOpen={openTraining} onGeneration={()=>setScreen('generation')} locked={busy||!!pending}/>}
    {loaded && <SessionPanel user={user} problem={problem} sessions={sessions} onChange={updateSessions} activity={activity} api={api} />}</div>
    <div className="training-view" hidden={screen !== 'generation'}>{screen === 'generation' && <AiOperations api={api} userId={user.id} initialMode={generationMode} ruleDraft={ruleDraft} onOpen={async generatedVersion => {
      if (busy || pending) throw new Error('진행 중인 제출을 먼저 마쳐 주세요.');
      const items=await api('/api/problems');setProblems(items);chooseProblem(generatedVersion);
    }} />}</div>
    <div className="practice-view" hidden={screen !== 'practice'}>
    <nav className="workspace-tools" aria-label="풀이 영역">
        {problems.length > 1 && <label className="solve-problem-choice"><span className="sr-only">풀이할 문제</span><select value={version} disabled={busy || !!pending} aria-describedby={busy || pending || activeSession ? "problem-selection-status" : undefined}
          onChange={event => { try{localStorage.setItem(selectionKey,event.target.value);}catch{} setVersion(event.target.value); restoreDraft(event.target.value);setInspected(null); }}>
          {problems.map(item => <option key={item.version} value={item.version}>{item.problemHeld?'[검토 중] ':''}{item.title} · {item.version}</option>)}
        </select></label>}

      <button aria-pressed={!resultsOpen&&mobilePane==='problem'} onClick={()=>{panelRevision.current++;setResultsOpen(false);setMobilePane('problem');}}>문제 보기</button>
      <button className="code-pane-switch" aria-pressed={mobilePane==='code'} onClick={()=>setMobilePane('code')}>코드 작성</button>
      <div className="tool-buttons">{[['history','제출 기록'],['feedback','피드백']].map(([key,name])=><button id={'tool-'+key} key={key} aria-expanded={resultsOpen&&tool===key} aria-controls="workspace-results" className={resultsOpen&&tool===key?'active':''} onClick={()=>showTool(key)}>{name}</button>)}</div>
    </nav>
    {problem && <div className="practice-grid" data-mobile-pane={mobilePane} data-results-open={resultsOpen} style={{'--result-width':`${resultSize}px`,'--problem-share':`${size.ratio}fr`,'--editor-share':`${100-size.ratio}fr`}}>
      <article className="problem-card">
        <span className="version">문제 · {problem.version}</span><h2 id="problem-title" tabIndex={-1}>{problem.title}</h2>
        <LimitChips profile={inspected?inspected.execution:problem.languages?.find(l=>l.id===language)} label={inspected?recordLanguageLabel(inspected):(problem.languages?.find(l=>l.id===language)||languageInfo[language])?.label}/><p>{problem.statement}</p>
        <Examples examples={problem.examples?.length?problem.examples:[{input:problem.sampleInput,output:problem.sampleOutput}]}/>
        {problem.problemHeld&&<p className="notice">문제 검토 중 · {problem.reviewReason} · 기존 코드와 기록은 보존되며 새 실행·제출·분석은 보류됩니다.</p>}
        {!problem.problemHeld&&<ProblemTeaching key={version} version={version} api={api} />}
        <p className="muted">{language==='JAVA'?'클래스 이름은 Main으로 작성해 주세요. ':''}제출한 코드는 기록에서 다시 확인할 수 있어요.</p>
      </article>
      <ResizeHandle className="problem-resizer" label="문제와 편집기 비율" value={size.ratio} min={20} max={70} step={2} scale={splitScale} onChange={ratio=>changeSize({ratio})}/>
      <div className="editor-column">
      <form id="code-form" className="editor-card" onSubmit={submit}>
        {(busy || pending || activeSession) && <p id="problem-selection-status" className="draft-help">
          {busy ? '요청 처리 중에는 문제를 변경할 수 없어요.' : pending ? '이전 제출의 접수를 확인한 뒤 문제를 변경할 수 있어요.' : currentSession
            ? '이 문제의 제출은 진행 중인 훈련에 저장돼요. 다른 문제도 자유롭게 선택할 수 있어요.'
            : `${activeSession.problemVersion} 훈련은 유지 중이에요. 현재 문제의 제출은 자유 풀이로 저장돼요.`}
        </p>}
        <div className="code-heading"><span>{inspected?languageInfo[recordLanguage(inspected)].file:lang.file}</span>
          <span className="code-tools"><label className="language-choice"><span className="visually-hidden">언어</span><select aria-label="풀이 언어" value={inspected?recordLanguage(inspected):language} disabled={busy||!!pending||!!inspected} onChange={e=>changeLanguage(e.target.value)}>{(inspected?[{id:recordLanguage(inspected),label:recordLanguageLabel(inspected)}]:(problem.languages||[languageInfo.JAVA])).map(l=><option key={l.id} value={l.id}>{l.label}</option>)}</select></label>
          {!inspected&&<details id="editor-tools" className="tool-pop"><summary>도구</summary><div className="tool-pop-panel">
            <strong>편집기 단축키 · 자동완성</strong><span>Ctrl+Space 후보 · Enter 확정 · Tab 들여쓰기 · Esc 다음 Tab으로 나가기 · Ctrl/⌘+F 검색 · Ctrl/⌘+Enter 제출</span>
            <strong>파일</strong>
            <label className="file-import">{language==='JAVA'?'Java':lang.label} 파일 불러오기<input type="file" accept={lang.extension} disabled={busy} onChange={importSource} /></label>
            <button type="button" className="secondary" onClick={downloadSource}>{lang.file} 내려받기</button>
            <span>초안은 계정·문제·언어별로 이 브라우저에만 남아요. 다른 기기로 옮길 때는 파일을 내려받아 주세요.</span></div></details>}
          {!inspected&&<ResetCode disabled={busy||!!pending} onReset={()=>{editSource(starters[language]);setDraftStatus('기본 템플릿으로 초기화했어요. 편집기에서 Ctrl+Z(Mac은 Cmd+Z)로 되돌릴 수 있어요.');}}/>}</span></div>
        <p className="draft-help editor-meta" title={inspected?'':draftStatus}><span className="draft-status" hidden={!!inspected} aria-live="polite">{draftStatus}</span></p>
        {inspected&&<div className="snapshot-tabs"><button type="button" className="secondary" onClick={()=>setInspected(null)}>작성 중인 코드로 돌아가기</button><span id="snapshot-heading" tabIndex={-1}>기록 코드 · 읽기 전용<br/><small>{inspected.problemVersion} · {new Date(inspected.createdAt).toLocaleString('ko-KR')}</small></span></div>}
        <div className="editor-views" style={size.height==null?undefined:{flex:`0 0 ${size.height}px`,height:size.height}}><div className="editor-view" hidden={!!inspected}>
        <CodeEditor key={`${user.id}:${version}:${language}`} language={language} label={lang.file} value={source} disabled={busy} onChange={editSource}
          onLimit={() => setError('너무 긴 코드는 입력할 수 없어요. 기존 내용을 유지했어요. 제출 코드는 UTF-8 기준 64 KiB 이내여야 해요.')}
          onSubmit={() => document.getElementById('code-form')?.requestSubmit()} />
        </div>{inspected&&<div className="editor-view"><CodeEditor key={inspected.id} id="snapshot-source" label="기록 코드" language={recordLanguage(inspected)} value={inspected.source||''} disabled={true} onChange={()=>{}} onSubmit={()=>{}} onLimit={()=>{}} /></div>}</div>
        <ResizeHandle label="편집기 높이 조절" orientation="horizontal" value={size.height} min={160} max={1000} step={20} onChange={height=>changeSize({height})}/>
        {!problem.submissionsEnabled && !problem.problemHeld && <p className="notice">코드 채점을 준비하고 있어요. 지금은 문제를 읽고 풀이를 작성할 수 있어요.</p>}
        {pending && <p className="notice">이전 제출의 접수 여부를 다시 확인합니다. 그때 보낸 코드로 확인해요.</p>}
      </form>
        <RunConsole key={user.id} user={user} api={api} scope={version} disabled={!!inspected || !problem.submissionsEnabled}
          body={{problemVersion:version,source,language,sessionId:currentSession?.id || null}} examples={problem.examples?.length?problem.examples:[{input:problem.sampleInput||'',output:problem.sampleOutput||''}]}
          runRequest={runRequest} casesRequest={casesRequest} onCaseCount={setCaseCount} onActivity={() => setActivity(value => value + 1)}>
          {problem.problemHeld&&<p className="notice">문제 검토 중 · 새 실행은 보류돼요.</p>}
        </RunConsole>
        {selected&&selected.problemVersion===version&&<section className="submit-console" aria-label="제출 결과" data-verdict={selected.verdict||'PENDING'}>
          <strong>제출 결과 〉 {label(selected)}</strong>
          {selected.verdict&&verdictHelp[selected.verdict]&&<span className="draft-help">{verdictHelp[selected.verdict]}</span>}
          <button type="button" className="secondary" onClick={()=>showTool('history')}>제출 기록·피드백 보기</button>
          <SubmitTests submission={selected}/>
        </section>}
        <div className="editor-actions">
          <button type="button" className="secondary" disabled={!!inspected} onClick={() => setCasesRequest(value=>value+1)}>테스트 케이스 추가{caseCount?` (${caseCount})`:''}</button>
          <button type="button" className="secondary" disabled={!!inspected || busy || (!problem.submissionsEnabled)} onClick={() => setRunRequest(value=>value+1)}>코드 실행</button>
          <button form="code-form" className="primary" disabled={!!inspected || busy || (!problem.submissionsEnabled && !pending)}>
          {busy ? '제출 확인 중…' : pending ? '같은 제출 다시 확인' : '제출 후 채점하기'}</button></div>
    {error && <p role="alert" className="notice error">{error}</p>}
    {notice && <p role="status" className="notice success">{notice}</p>}
    </div>
    <div className="panel-resizer" role="separator" tabIndex={resultsOpen?0:-1} hidden={!resultsOpen} aria-label="결과 패널 너비" aria-orientation="vertical" aria-valuemin={300} aria-valuemax={520} aria-valuenow={resultSize}
      onPointerDown={resizePanel} onPointerMove={dragPanel} onPointerUp={event=>event.currentTarget.releasePointerCapture(event.pointerId)}
      onKeyDown={event=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(event.key)){event.preventDefault();setResultSize(value=>event.key==='Home'?300:event.key==='End'?520:Math.max(300,Math.min(520,value+(event.key==='ArrowLeft'?20:-20)*(window.innerWidth>=1440?1:-1))));}}} />
    <aside id="workspace-results" className="result-dock" hidden={!resultsOpen} aria-label="실행과 제출 결과">
      <div className="result-heading"><h3>{tool==='history'?'제출 기록':'피드백'}</h3><button type="button" className="secondary" onClick={closeResults}>결과 접기</button></div>
      <div className="result-content" id="submission-results">
        <RecordHistory title="최근 제출 내역" items={history.filter(item=>item.problemVersion===version)} selectedId={selected?.id} open={historyOpen} onToggle={setHistoryOpen} onSelect={open} label={label}/>
        {(!selected||selected.problemVersion!==version)&&<p className="muted">제출 내역을 펼쳐 확인할 기록을 선택해 주세요.</p>}
        {selected&&selected.problemVersion===version&&<article className="submission-detail">
          {selected.problemHeld&&<p className="notice">문제 검토 중 · 이 기록은 학습 판단 근거에서 보류됩니다.</p>}
          <div className="record-heading"><h4 id="submission-heading" tabIndex={-1}>{label(selected)}</h4><small>{new Date(selected.createdAt).toLocaleString('ko-KR')}</small></div>
          {verdictHelp[selected.verdict]&&selected.verdict!=='IE'&&<p className="draft-help">{verdictHelp[selected.verdict]}</p>}
          <p className="version">{selected.problemVersion} · {recordLanguageLabel(selected)}</p>
          {(selected.problemVersion!==version||selected.source!==source)&&<p className="notice">현재 편집 중인 코드와 다른 제출의 결과예요.</p>}
          <button type="button" className="secondary" onClick={()=>viewCode(selected)}>해당 제출 코드 보기</button>
          {tool==='history'&&<>
            {selected.verdict==='IE'&&<p className="notice">채점 시스템 문제로 결과를 확인하지 못했어요. 풀이 실패로 기록하지 않습니다.</p>}
            {selected.compileMessage&&<pre className="compiler-message">{selected.compileMessage}</pre>}
            <details className="saved-code"><summary>제출 코드 펼치기</summary><pre aria-label="저장된 제출 코드">{selected.source}</pre></details>
            <button type="button" className="secondary" onClick={()=>showTool('feedback')}>이 제출 피드백 보기</button>
          </>}
          <div hidden={tool!=='feedback'}><AiFeedback key={selected.id} submission={selected} api={api}/></div>
        </article>}
      </div>
    </aside></div>}
    {!loaded && !error && <p role="status">문제와 내 제출 기록을 불러오고 있어요…</p>}
    {!problem && error && <p role="alert" className="notice error">{error}</p>}
    {loaded && !problem && <p className="muted">현재 풀이할 수 있는 문제가 없어요.</p>}
    </div>
  </section>;
}
