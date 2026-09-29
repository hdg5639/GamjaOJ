'use client';
import {useEffect,useRef,useState} from 'react';
import {languageInfo,starters,recordLanguageLabel,limitText} from './languages';
import RunConsole,{SubmitTests,Examples} from './run-console';
import LimitChips from './limit-chips';
import {useVimMode} from './editor-settings';
import {scheduleServerDraft,loadServerDraft,readLocalDraft,writeLocalDraft,newer} from './server-drafts';
import dynamic from 'next/dynamic';
import {useEditorSizing,ResizeHandle,splitScale} from './editor-sizing';
import Modal from './modal';
import DiagnosticEvaluation from './diagnostic-evaluation';
import DiagnosticReassessment from './diagnostic-reassessment';
import {categoryLabels as categories,bankTitle} from './diagnostic-categories';
import ResetCode from './reset-code';
import {verdictText,verdictHelp} from './verdicts';
const Editor=dynamic(()=>import('./code-editor'),{ssr:false});
const starter='import java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n    }\n}\n';
const outcomes={OPEN:'아직 완료하지 않음',PASSED:'통과',EXHAUSTED:'5회 소진',SKIPPED:'건너뜀'};
export default function DiagnosticPanel({user,api,onPractice,onOpen,onGeneration,onRuleDraft}) {
  const [size,changeSize]=useEditorSizing(user.id,'diagnostic',50,390);
  const [sheet,setSheet]=useState(null);
  const [vim,setVim]=useVimMode();
  const [banks,setBanks]=useState([]),[sessions,setSessions]=useState([]),[session,setSession]=useState(null);
  const [error,setError]=useState(''),[busy,setBusy]=useState(false),[loaded,setLoaded]=useState(false);
  const [language,setLanguage]=useState('JAVA'),[finishing,setFinishing]=useState(false);
  const [source,setSource]=useState(starter),[result,setResult]=useState(null),[held,setHeld]=useState(null),[saveNote,setSaveNote]=useState('');
  const [request,setRequest]=useState(null),[records,setRecords]=useState([]),[record,setRecord]=useState(null);
  const [scope,setScope]=useState({});
  const [runRequest,setRunRequest]=useState(0),[casesRequest,setCasesRequest]=useState(0),[caseCount,setCaseCount]=useState(0);
  const lock=useRef(false),revision=useRef(0),active=useRef(null),heading=useRef(null),draftTicket=useRef(0),submitted=useRef(null);
  const requestKey=`gamjaoj-diagnostic-request-${user.id}`;
  // A finished item stays on screen (read-only, with its result) until the learner moves on with 다음 문제.
  const current=held?.question||session?.current,item=session?.items.find(i=>i.id===current?.itemId);
  // Items arrive ordered by bank position; the learner sees 1..N within this session (selected fields only).
  const number=id=>(session?.items.findIndex(i=>i.id===id)??-1)+1;
  const draftKey=(id,chosen=language)=>`gamjaoj-diagnostic-draft-${user.id}-${id}${chosen==='JAVA'?'':'-'+chosen}`;
  const languageKey=`gamjaoj-diagnostic-language-${user.id}`;
  function loadDraft(id,chosen){
    let local=null;try{local=readLocalDraft(draftKey(id,chosen));}catch{}
    setSource(local?.source??(chosen==='JAVA'?starter:starters[chosen]));
    const ticket=++draftTicket.current;
    loadServerDraft(api,'d:'+id,chosen).then(server=>{
      if(ticket!==draftTicket.current||!newer(local,server)||server.source.length>65536)return;
      setSource(server.source);try{writeLocalDraft(draftKey(id,chosen),server.source,Date.parse(server.updatedAt));}catch{}
    });
  }
  function changeLanguage(chosen){if(busy||request)return;setLanguage(chosen);try{localStorage.setItem(languageKey,chosen);}catch{}loadDraft(current.itemId,chosen);}
  function accept(next){
    const prior=active.current;
    if(prior?.current&&prior.current.itemId!==next.current?.itemId){
      const closed=next.items.find(i=>i.id===prior.current.itemId);
      // Finished by a formal submission (passed or out of attempts): keep it until 다음 문제. Skips move on at once.
      if(submitted.current===prior.current.itemId&&['PASSED','EXHAUSTED'].includes(closed?.status))setHeld(h=>h||{question:prior.current});
    }
    active.current=next;setSession(next);
  }
  async function refresh(){
    const generation=revision.current;
    const [available,history]=await Promise.all([api('/api/diagnostics/banks'),api('/api/diagnostics')]);
    if(generation!==revision.current)return;
    setBanks(available);setSessions(history);setLoaded(true);
    const next=history.find(s=>s.id===active.current?.id)||history.find(s=>s.status!=='COMPLETED');
    if(next)accept(next);
  }
  useEffect(()=>{
    try{const saved=JSON.parse(sessionStorage.getItem(requestKey));if(saved?.key&&saved?.path&&saved?.body)setRequest(saved);}catch{}
    refresh().catch(e=>setError(e.message));
    return()=>{revision.current++;};
  },[]);
  useEffect(()=>{
    if(!current)return;
    let chosen='JAVA';try{const pending=JSON.parse(sessionStorage.getItem(requestKey));chosen=pending?.body?.source!=null ? (pending.body.language||'JAVA') : (localStorage.getItem(languageKey)||'JAVA');}catch{}
    if(!(current.languages||[languageInfo.JAVA]).some(l=>l.id===chosen))chosen='JAVA';
    setLanguage(chosen);
    loadDraft(current.itemId,chosen);
    setRecord(null);heading.current?.focus();
  },[current?.itemId]);
  useEffect(()=>{
    if(!session||session.status==='COMPLETED')return;
    let stopped=false,running=false;
    const timer=setInterval(async()=>{if(running||lock.current)return;running=true;
      const generation=revision.current;
      try{const next=await api(`/api/diagnostics/${session.id}`);if(!stopped&&!lock.current&&generation===revision.current)accept(next);}
      catch(e){if(!stopped)setError(e.message);}finally{running=false;}
    },2000);
    return()=>{stopped=true;clearInterval(timer);};
  },[session?.id,session?.status]);
  useEffect(()=>{
    if(!result||result.status==='FINISHED')return;
    let stopped=false;
    const path=result.input==null?'submissions':'runs';
    const timer=setInterval(async()=>{try{const next=await api(`/api/${path}/${result.id}`);if(!stopped)setResult(next);}catch(e){if(!stopped)setError(e.message);}},1800);
    return()=>{stopped=true;clearInterval(timer);};
  },[result?.id,result?.status]);
  async function mutate(path,body,retain=false){
    if(lock.current)return;lock.current=true;revision.current++;setBusy(true);setError('');
    const pending=retain?(request||{path,body,key:crypto.randomUUID()}):{path,body};
    if(retain){setRequest(pending);try{sessionStorage.setItem(requestKey,JSON.stringify(pending));}catch{}}
    try{
      const data=await api(pending.path,{method:'POST',headers:{'Content-Type':'application/json',...(pending.key?{'Idempotency-Key':pending.key}:{})},body:JSON.stringify(pending.body)});
      if(retain){setRequest(null);try{sessionStorage.removeItem(requestKey);}catch{}}
      if(data.items)accept(data);else{setResult(data);if(path==='/api/submissions')submitted.current=body.diagnosticItemId;}
      await refresh();
    }catch(e){
      if(retain&&e.status>=400&&e.status<500){setRequest(null);try{sessionStorage.removeItem(requestKey);}catch{}}
      setError(e.message);
    }finally{lock.current=false;setBusy(false);}
  }
  function edit(value){
    setSource(value);draftTicket.current++;
    try{writeLocalDraft(draftKey(current.itemId),value);}catch{setError('브라우저 저장 공간을 사용할 수 없어 초안을 보관하지 못했어요.');}
    const id=current.itemId;setSaveNote('초안을 저장하고 있어요…');
    scheduleServerDraft(api,'d:'+id,language,value,ok=>setSaveNote(ok?'초안을 서버에 저장했어요.':'서버에 저장하지 못했어요. 이 브라우저에는 저장돼 있어요.'));
  }
  async function history(){try{
    const rows=await api(current?`/api/submissions?problemVersion=${encodeURIComponent(current.problemVersion)}`:'/api/submissions');const ids=new Set(session.items.map(i=>i.id));
    setRecords(rows.filter(r=>ids.has(r.diagnosticItemId)));setError('');
  }catch(e){setError(e.message);}}
  const [problemExpanded,setProblemExpanded]=useState(false);
  const disabled=busy||!!request||!!held||session?.status!=='ACTIVE';
  function next(){setHeld(null);submitted.current=null;setResult(null);}
  const body={problemVersion:current?.problemVersion,source,language,diagnosticItemId:current?.itemId};
  const recordsView=session&&<><ul>{session.items.map(i=><li key={i.id}>{number(i.id)}. {categories[i.category]||i.category} · {i.externallySeen?'본 적 있음 · 평가 근거에서 제외':outcomes[i.status]} · 제출 {i.attempts}회{session.sourceSessionId&&session.status==='COMPLETED'&&!i.externallySeen&&<button className="secondary" disabled={busy||!!request} onClick={()=>mutate(`/api/diagnostics/${session.id}/items/${i.id}/exposure`,{},true)}>{number(i.id)}번 문항 · 이전에 본 문제로 정정</button>}</li>)}</ul><p>미완료·건너뛴 문항은 약점으로 판정하지 않습니다.</p>{session.sourceSessionId&&session.status==='COMPLETED'&&<p>이전에 본 문제로 정정하면 기존 판정은 유지하고 해당 문항을 새 평가 근거에서 제외합니다. 이전 해석과 계획은 보류되며, 위에서 평가를 다시 요청할 수 있어요. 정정은 되돌리지 않습니다.</p>}<button className="secondary" onClick={history}>최근 제출 기록 불러오기</button>{records.filter(r=>!current||r.problemVersion===current.problemVersion).map(r=><button className="secondary" key={r.id} onClick={()=>api(`/api/submissions/${r.id}`).then(setRecord).catch(e=>setError(e.message))}>{verdictText(r.verdict)||'채점 중'} · {recordLanguageLabel(r)} · {new Date(r.createdAt).toLocaleString()}</button>)}{record&&(!current||record.problemVersion===current.problemVersion)&&<><p>{recordLanguageLabel(record)} · {limitText(record.execution)}</p><pre aria-label="제출 당시 코드">{record.source}</pre></>}</>;
  return <section className="diagnostic-panel" data-solving={!!current} aria-label="선택 진단">
    <div className="diagnostic-heading">
      {!current&&<div><h2>{session?'진단 진행':'나에게 맞는 시작점 찾기'}</h2>{!session&&<p className="muted">내 약점을 몰라도 시작할 수 있어요. 원하는 분야만 풀고, 언제든 일반 연습으로 돌아가세요.</p>}</div>}
      {session&&<div className="diagnostic-session-status"><span>{session.items.filter(i=>i.status!=='OPEN').length} / {session.items.length}문항 완료 · {session.status==='PAUSED'?'일시정지':session.status==='COMPLETED'?'진단 종료':'진행 중'}</span><progress className="diagnostic-progress" aria-label="진단 완료 문항" max={session.items.length||1} value={session.items.filter(i=>i.status!=='OPEN').length}/></div>}
      <div className="diagnostic-session-actions">{session&&session.status!=='COMPLETED'&&<button className="secondary" onClick={()=>setSheet('records')}>진단 기록</button>}{session&&session.status!=='COMPLETED'&&<button className="secondary" disabled={busy||!!request} onClick={()=>mutate(`/api/diagnostics/${session.id}/state`,{status:session.status==='PAUSED'?'ACTIVE':'PAUSED'})}>{session.status==='PAUSED'?'진단 이어서 풀기':'일시정지'}</button>}
      {session&&session.status!=='COMPLETED'&&!finishing&&<button className="secondary" disabled={busy||!!request} onClick={()=>setFinishing(true)}>진단 끝내기</button>}
      <button className="secondary" onClick={onPractice}>일반 연습으로</button>
      <button className="secondary" disabled={busy} onClick={()=>refresh().catch(e=>setError(e.message))}>목록 새로고침</button></div>
      {session&&session.status!=='COMPLETED'&&finishing&&<div className="notice" role="group" aria-label="진단 끝내기 확인">
        <p>남은 {session.items.filter(i=>i.status==='OPEN').length}문항은 건너뜀으로 기록하고 이 진단을 끝냅니다. 건너뛴 문항은 약점이 아니라 미평가로 남고, 끝낸 뒤에는 이 진단을 다시 이어서 풀 수 없어요. 푼 문항의 기록과 평가 요청은 그대로 사용할 수 있어요.</p>
        <button className="primary" disabled={busy||!!request} onClick={async()=>{await mutate(`/api/diagnostics/${session.id}/finish`,{});setFinishing(false);}}>남은 문항 건너뛰고 끝내기</button>
        <button className="secondary" disabled={busy} onClick={()=>setFinishing(false)}>계속 풀기</button></div>}
    </div>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {!loaded&&<p role="status">진단 목록을 불러오는 중…</p>}
    {request&&<p className="notice">응답을 확인하지 못한 요청이 있어요. 같은 요청으로 결과를 확인하세요. <button disabled={busy} onClick={()=>mutate(request.path,request.body,true)}>요청 다시 확인</button></p>}
    {!session&&loaded&&<>
      <p>문제당 정식 제출은 최대 5회이며, 정답 또는 5회 소진 시 다음 문항으로 넘어갑니다. 직접 실행은 횟수 제한이 없으며, 동시에 실행할 수 있는 작업 수는 제한됩니다.</p>
      {!banks.length&&<p className="notice">검토가 끝난 진단 문항을 준비하고 있어요. 지금은 일반 문제를 자유롭게 연습할 수 있어요.</p>}
      {banks.map(bank=><fieldset key={bank.id} disabled={busy||!!request}><legend>{bankTitle(bank.id)} · {bank.questionCount}문항</legend>
        {bank.id.startsWith('core-a-')&&<p>구현·배열/문자열·기초 자료구조·기초 탐색을 확인하는 시범 진단입니다. 하·중 난이도는 잠정 분류이며, 완료 시간과 학습 효과는 아직 실측 검증되지 않았습니다. 전체 분야의 숙련도를 판정하지 않습니다.</p>}
        {bank.id.startsWith('algo-mix-a-')&&<p>배열·문자열부터 BFS·DFS·백트래킹·DP·이분 탐색·그리디·최단 경로·최소 신장 트리까지 분야별 하·중 문항으로 풀이 과정과 코드 습관을 관찰합니다. 원하는 분야만 골라 시작할 수 있어요. 전체를 한 언어로 푸는 데 약 100~120분을 예상하지만 실측 전 추정이며, 숙련도 점수를 매기지 않습니다.</p>}
        {bank.categories.map(c=><label key={c}><input type="checkbox" checked={(scope[bank.id]||bank.categories).includes(c)} onChange={e=>setScope({...scope,[bank.id]:e.target.checked?[...(scope[bank.id]||bank.categories),c]:(scope[bank.id]||bank.categories).filter(x=>x!==c)})}/>{categories[c]||c} · 하·중 2문항</label>)}
        <button className="primary" disabled={!(scope[bank.id]||bank.categories).length} onClick={()=>mutate('/api/diagnostics',{bankId:bank.id,categories:scope[bank.id]||bank.categories},true)}>선택한 {(scope[bank.id]||bank.categories).length*2}문항 시작</button>
      </fieldset>)}
    </>}
    {session&&<>
      {current&&<div className="diagnostic-workspace" style={{'--problem-share':`${size.ratio}fr`,'--editor-share':`${100-size.ratio}fr`}}><article data-expanded={problemExpanded}><h2 ref={heading} tabIndex={-1}>{current.title}</h2><p><strong>{number(item.id)}번 / {session.items.length}문항</strong> · {categories[item.category]||item.category} · {item.difficulty==='EASY'?'하':'중'} · 제출 {item.attempts}/5{item.pending?' · 채점 중':''}</p><LimitChips profile={current.languages?.find(l=>l.id===language)} label={current.languages?.find(l=>l.id===language)?.label}/><button className="diagnostic-problem-toggle secondary" aria-expanded={problemExpanded} aria-controls="diagnostic-problem-content" onClick={()=>setProblemExpanded(value=>!value)}>{problemExpanded?'문제 접기':'문제 보기'}</button><div id="diagnostic-problem-content"><p className="diagnostic-statement">{current.statement}</p><Examples examples={current.examples?.length?current.examples:[{input:current.sampleInput,output:current.sampleOutput}]}/></div></article>
        <ResizeHandle className="diagnostic-resizer" label="진단 문제와 편집기 비율" value={size.ratio} min={20} max={70} step={2} scale={splitScale} onChange={ratio=>changeSize({ratio})}/>
        <div className="diagnostic-code-column"><div className="code-tools"><label className="language-choice">언어<select aria-label="진단 언어" title="언어를 바꿔도 제출 횟수는 유지돼요." value={language} disabled={disabled||!!item.pending} onChange={e=>changeLanguage(e.target.value)}>{(current.languages||[languageInfo.JAVA]).map(l=><option key={l.id} value={l.id}>{l.label}</option>)}</select></label>
        <button type="button" className="secondary vim-chip" aria-pressed={vim} title="Vim 키 바인딩 (Esc 명령 모드 · i 입력 모드)" onClick={()=>setVim(!vim)}>Vim {vim?'켬':'끔'}</button>
        <ResetCode disabled={disabled||!!item.pending} onReset={()=>edit(language==='JAVA'?starter:starters[language])}/></div>
        <div className="diagnostic-editor" style={{height:size.height}}><Editor key={`${current.itemId}:${language}`} language={language} id="diagnostic-source" label={`진단 ${language==='JAVA'?'Java':languageInfo[language].label} 코드`} value={source} vim={vim} disabled={disabled} onChange={edit} onSubmit={()=>{if(!disabled&&!item.pending)mutate('/api/submissions',body,true);}} onLimit={()=>setError('코드는 64 KiB 이내로 작성해 주세요.')}/></div>
          <ResizeHandle label="진단 편집기 높이 조절" orientation="horizontal" value={size.height} min={160} max={1000} step={20} onChange={height=>changeSize({height})}/>
          <p className="muted">{saveNote||'초안은 자동 저장돼요.'} 진단 중에는 해설과 AI 힌트를 제공하지 않습니다.</p>
          {session.sourceSessionId&&<div><p className="muted">이 문제나 풀이를 이미 알고 있으면 아래에 알려 주세요. 기록 후 건너뛰며 약점이나 독립적인 실력 향상 근거로 쓰지 않습니다.</p><button className="secondary" disabled={disabled||!!item.pending} onClick={()=>mutate(`/api/diagnostics/${session.id}/items/${current.itemId}/exposure`,{},true)}>이 문제나 풀이를 본 적 있어요 · 기록 후 건너뛰기</button></div>}
          <RunConsole user={user} api={api} scope={current.problemVersion} disabled={disabled} examples={current.examples?.length?current.examples:[{input:current.sampleInput,output:current.sampleOutput}]}
            body={body} runRequest={runRequest} casesRequest={casesRequest} onCaseCount={setCaseCount}
            submission={result&&result.input==null&&result.diagnosticItemId===current.itemId?result:null}/>
          {held?<div className="editor-actions"><span className="held-note">{item?.status==='PASSED'?'통과했어요.':'정식 제출 5회를 모두 사용했어요.'} 결과를 확인한 뒤 다음으로 넘어가세요.</span>
            <button className="primary" onClick={next}>{session.current?'다음 문제':'진단 결과 보기'}</button></div>
          :<div className="editor-actions">
            <button className="secondary" disabled={disabled||!!item.pending} onClick={()=>mutate(`/api/diagnostics/${session.id}/items/${current.itemId}/skip`,{})}>모르겠어요 · 건너뛰기</button>
            <button className="secondary" disabled={disabled} onClick={()=>setCasesRequest(value=>value+1)}>테스트 케이스 추가{caseCount?` (${caseCount})`:''}</button>
            <button className="secondary" disabled={disabled} onClick={()=>setRunRequest(value=>value+1)}>코드 실행</button>
            <button className="primary" disabled={disabled||!!item.pending} onClick={()=>mutate('/api/submissions',body,true)}>정식 제출 ({5-item.attempts}회 남음)</button>
          </div>}
        </div></div>}

      {session.status==='COMPLETED'&&!held&&<p className="notice">진단을 마쳤어요. 판정 기록을 확인하고 아래에서 종합 평가를 요청할 수 있어요.</p>}
      {session.status==='COMPLETED'&&!held&&<DiagnosticReassessment key={`reassessment-${session.id}`} api={api} session={session} busy={busy||!!request} onStart={mutate}/> }
      {session.status==='COMPLETED'&&!held&&<DiagnosticEvaluation key={`evaluation-${session.id}`} api={api} session={session} onOpen={onOpen} onGeneration={onGeneration} onRuleDraft={onRuleDraft} />}
      {session.status==='COMPLETED'?!held&&<details><summary>문항별 진행과 제출 기록</summary>{recordsView}</details>
        :<Modal open={sheet==='records'} title="진단 기록" onClose={()=>setSheet(null)} wide><h4>문항별 진행과 제출 기록</h4>{recordsView}<DiagnosticEvaluation key={`evaluation-${session.id}`} api={api} session={session} onOpen={onOpen} onGeneration={onGeneration} onRuleDraft={onRuleDraft} /></Modal>}
      {session.status==='COMPLETED'&&!held&&<button className="secondary" onClick={()=>{active.current=null;setSession(null);setHeld(null);setRecords([]);setRecord(null);}}>다른 진단 보기</button>}
    </>}
    {!session&&sessions.length>0&&<details><summary>지난 진단</summary>{sessions.map(s=><button className="secondary" key={s.id} onClick={()=>accept(s)}>{s.items.length}문항 · {s.status==='COMPLETED'?'완료':'이어서 보기'}</button>)}</details>}
  </section>;
}

