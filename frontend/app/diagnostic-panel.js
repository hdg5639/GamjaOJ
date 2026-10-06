'use client';
import LoadingIndicator from './loading-indicator';
import {callableLanguage} from './callable-language';
import ProblemStatement from './problem-statement';
import SelectControl from './select-control';
import DiagnosticChooser from './diagnostic-chooser';

import EditorShortcutHelp,{EditorTools} from './editor-shortcut-help';
import {useEffect,useRef,useState} from 'react';
import {languageInfo,starters,recordLanguageLabel,limitText} from './languages';
import RunConsole,{SubmitTests,Examples} from './run-console';
import LimitChips from './limit-chips';
import SplitStack,{useSplit,useSolvingLayout,usePaneOrder,columnLayoutStyle,columnScale,SolvingLayoutChoice} from './split-stack';
import {useVimMode} from './editor-settings';
import {scheduleServerDraft,loadServerDraft,readLocalDraft,writeLocalDraft,newer} from './server-drafts';
import dynamic from 'next/dynamic';
import {useEditorSizing,ResizeHandle,splitScale} from './editor-sizing';
import Modal from './modal';
import DiagnosticEvaluation from './diagnostic-evaluation';
import DiagnosticReassessment from './diagnostic-reassessment';
import {categoryLabels as categories,bankTitle,diagnosticRoleLabels} from './diagnostic-categories';
import ResetCode from './reset-code';
import ListPagination from './list-pagination';
import {skipReasons,diagnosticOutcome} from './diagnostic-outcomes';
import {verdictText,verdictHelp} from './verdicts';
const Editor=dynamic(()=>import('./code-editor'),{ssr:false});
const starter='import java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n    }\n}\n';
const outcomes={OPEN:'아직 완료하지 않음',PASSED:'통과',EXHAUSTED:'5회 소진',SKIPPED:'건너뜀'};
export default function DiagnosticPanel({user,api,onPractice,onOpen,onGeneration,onRuleDraft,onLearning,requestedReport,visible=true}) {
  const [size,changeSize]=useEditorSizing(user.id,'diagnostic',50,390);
  const [sheet,setSheet]=useState(null),[historyPage,setHistoryPage]=useState(1);
  const [split,setSplit]=useSplit(user.id,'diagnostic',62);
  const [layout,setLayout]=useSolvingLayout(user.id);
  const [columnSplit,setColumnSplit]=useSplit(user.id,'diagnostic-columns');
  const [paneOrder,setPaneOrder,mirrored,setMirrored]=usePaneOrder(user.id,layout);
  const [firstColumn,setFirstColumn]=useSplit(user.id,'diagnostic-first-column',33);
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
    setSource(local?.source??(callableLanguage(current?.api,chosen)?.template||(chosen==='JAVA'?starter:starters[chosen])));
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
  const reportOpened=useRef(null);
  useEffect(()=>{
    if(!visible||!loaded||!requestedReport||reportOpened.current===requestedReport.key||busy||request)return;
    if(sessions.some(saved=>saved.status!=='COMPLETED')){reportOpened.current=requestedReport.key;setError('진행 중인 진단을 마친 뒤 이전 결과를 확인해 주세요.');return;}
    let live=true;api(`/api/diagnostics/${requestedReport.id}`).then(saved=>{if(!live)return;if(saved.status!=='COMPLETED')throw new Error('완료한 진단 결과를 선택해 주세요.');reportOpened.current=requestedReport.key;active.current=saved;setSession(saved);setHeld(null);setSheet(null);setRecords([]);setRecord(null);setError('');}).catch(e=>{if(live)setError(e.message);});
    return()=>{live=false;};
  },[visible,loaded,requestedReport?.key,busy,request]);
  useEffect(()=>{
    if(!current)return;
    let chosen='JAVA';try{const pending=JSON.parse(sessionStorage.getItem(requestKey));chosen=pending?.body?.source!=null ? (pending.body.language||'JAVA') : (localStorage.getItem(languageKey)||'JAVA');}catch{}
    if(!(current.languages||[languageInfo.JAVA]).some(l=>l.id===chosen))chosen='JAVA';
    setLanguage(chosen);
    loadDraft(current.itemId,chosen);
    setRecord(null);heading.current?.focus();
  },[current?.itemId]);
  useEffect(()=>{
    if(!visible||!session||session.status==='COMPLETED')return;
    let stopped=false,running=false;
    const timer=setInterval(async()=>{if(running||lock.current)return;running=true;
      const generation=revision.current;
      try{const next=await api(`/api/diagnostics/${session.id}`);if(!stopped&&!lock.current&&generation===revision.current)accept(next);}
      catch(e){if(!stopped)setError(e.message);}finally{running=false;}
    },2000);
    return()=>{stopped=true;clearInterval(timer);};
  },[session?.id,session?.status,visible]);
  useEffect(()=>{
    if(!visible||!result||result.status==='FINISHED')return;
    let stopped=false;
    const path=result.input==null?'submissions':'runs';
    const timer=setInterval(async()=>{try{const next=await api(`/api/${path}/${result.id}`);if(!stopped)setResult(next);}catch(e){if(!stopped)setError(e.message);}},1800);
    return()=>{stopped=true;clearInterval(timer);};
  },[result?.id,result?.status,visible]);
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
  const recordsView=session&&<><div className="diagnostic-fact-table"><table><caption>문항별 진행</caption><thead><tr><th>문항</th><th>분야</th><th>결과</th><th>제출</th><th>정정</th></tr></thead><tbody>{session.items.map(i=><tr key={i.id}><th scope="row">{number(i.id)}번</th><td>{categories[i.category]||i.category}</td><td>{diagnosticOutcome(i)}</td><td>{i.attempts}회</td><td>{session.sourceSessionId&&session.status==='COMPLETED'&&!i.externallySeen&&<button className="secondary" disabled={busy||!!request} onClick={()=>mutate(`/api/diagnostics/${session.id}/items/${i.id}/exposure`,{},true)}>{number(i.id)}번 문항 · 이전에 본 문제로 정정</button>}</td></tr>)}</tbody></table></div><p>접근 어려움은 본인 보고로 구분하며, 시간 부족·사유 미상은 미확인으로 남깁니다.</p>{session.sourceSessionId&&session.status==='COMPLETED'&&<p>이전에 본 문제로 정정하면 기존 판정은 유지하고 해당 문항을 새 평가 근거에서 제외합니다. 이전 해석과 계획은 보류되며, 위에서 평가를 다시 요청할 수 있어요. 정정은 되돌리지 않습니다.</p>}<button className="secondary" onClick={history}>최근 제출 기록 불러오기</button>{records.filter(r=>!current||r.problemVersion===current.problemVersion).map(r=><button className="secondary" key={r.id} onClick={()=>api(`/api/submissions/${r.id}`).then(setRecord).catch(e=>setError(e.message))}>{verdictText(r.verdict)||'채점 중'} · {recordLanguageLabel(r)} · {new Date(r.createdAt).toLocaleString()}</button>)}{record&&(!current||record.problemVersion===current.problemVersion)&&<><p>{recordLanguageLabel(record)} · {limitText(record.execution)}</p><pre aria-label="제출 당시 코드">{record.source}</pre></>}</>;
  function otherDiagnostics(){active.current=null;setSession(null);setHeld(null);setResult(null);setRecords([]);setRecord(null);setFinishing(false);setSheet(null);}
  function selectHistory(saved){revision.current++;setHeld(null);submitted.current=null;setResult(null);setRecords([]);setRecord(null);setFinishing(false);setSheet(null);accept(saved);}
  return <section className="diagnostic-panel" data-solving={!!current} aria-label="선택 진단">
    <div className="diagnostic-heading">
      {current&&<SolvingLayoutChoice value={layout} onChange={setLayout} order={paneOrder} onOrderChange={setPaneOrder} mirrored={mirrored} onMirrorChange={setMirrored}/>}
      {!current&&<div><h2>{session?(session.status==='COMPLETED'?'진단 완료':'진단 진행'):'나에게 맞는 시작점 찾기'}</h2>{!session&&<p className="muted">내 약점을 몰라도 시작할 수 있어요. 원하는 분야만 풀고, 언제든 일반 연습으로 돌아가세요.</p>}</div>}
      {session&&<div className="diagnostic-session-status">{session.bankId?.startsWith('exam-')&&<strong>{bankTitle(session.bankId)}{session.repeatAttempt?' · 풀어본 세트 재응시':''}</strong>}<span>{session.items.filter(i=>i.status!=='OPEN').length} / {session.items.length}문항 완료 · {session.status==='PAUSED'?'일시정지':session.status==='COMPLETED'?'진단 종료':'진행 중'}</span><progress className="diagnostic-progress" aria-label="진단 완료 문항" max={session.items.length||1} value={session.items.filter(i=>i.status!=='OPEN').length}/></div>}
      <div className="diagnostic-session-actions">{session?.status==='COMPLETED'&&!held&&<button className="secondary" onClick={otherDiagnostics}>다른 진단 보기</button>}{!current&&<button className="secondary" disabled={!sessions.length} onClick={()=>{setHistoryPage(1);setSheet('history');}}>지난 진단</button>}{session&&session.status!=='COMPLETED'&&<button className="secondary" onClick={()=>setSheet('records')}>진단 기록</button>}{session&&session.status!=='COMPLETED'&&<button className="secondary" disabled={busy||!!request} onClick={()=>mutate(`/api/diagnostics/${session.id}/state`,{status:session.status==='PAUSED'?'ACTIVE':'PAUSED'})}>{session.status==='PAUSED'?'진단 이어서 풀기':'일시정지'}</button>}
      {session&&session.status!=='COMPLETED'&&!finishing&&<button className="secondary" disabled={busy||!!request} onClick={()=>setFinishing(true)}>진단 끝내기</button>}
      <button className="secondary" onClick={onPractice}>일반 연습으로</button>
      <button className="secondary" disabled={busy} onClick={()=>refresh().catch(e=>setError(e.message))}>목록 새로고침</button></div>
      {session&&session.status!=='COMPLETED'&&finishing&&<div className="notice" role="group" aria-label="진단 끝내기 확인">
        <p>남은 {session.items.filter(i=>i.status==='OPEN').length}문항은 건너뜀으로 기록하고 이 진단을 끝냅니다. 남은 문항은 ‘종료로 미완료’로 남으며, 끝낸 뒤에는 이 진단을 다시 이어서 풀 수 없어요. 푼 문항의 기록과 평가 요청은 그대로 사용할 수 있어요.</p>
        <button className="primary" disabled={busy||!!request} onClick={async()=>{await mutate(`/api/diagnostics/${session.id}/finish`,{});setFinishing(false);}}>남은 문항 건너뛰고 끝내기</button>
        <button className="secondary" disabled={busy} onClick={()=>setFinishing(false)}>계속 풀기</button></div>}
    </div>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {!loaded&&<LoadingIndicator>진단 목록을 불러오는 중…</LoadingIndicator>}
    {request&&<p className="notice">응답을 확인하지 못한 요청이 있어요. 같은 요청으로 결과를 확인하세요. <button disabled={busy} onClick={()=>mutate(request.path,request.body,true)}>요청 다시 확인</button></p>}
    {!session&&loaded&&<DiagnosticChooser banks={banks} scope={scope} setScope={setScope} disabled={busy||!!request} onStart={(bank,chosen)=>mutate('/api/diagnostics',{bankId:bank.id,...(chosen?{categories:chosen}:{})},true)}/>}
    {session&&<>
      {current&&<div className="diagnostic-workspace" data-layout={layout} data-pane-order={paneOrder} data-mirrored={mirrored} style={{...columnLayoutStyle(paneOrder,firstColumn,columnSplit,mirrored,size.ratio,split),'--problem-share':`${size.ratio}fr`,'--editor-share':`${100-size.ratio}fr`}}><article key={current.problemVersion} data-expanded={problemExpanded}><h2 ref={heading} tabIndex={-1}>{current.title}</h2><p><strong>{number(item.id)}번 / {session.items.length}문항</strong> · {categories[item.category]||item.category} · {diagnosticRoleLabels[item.difficulty]||item.difficulty} · 제출 {item.attempts}/5{item.pending?' · 채점 중':''}</p><LimitChips profile={current.languages?.find(l=>l.id===language)} label={current.languages?.find(l=>l.id===language)?.label}/><button className="diagnostic-problem-toggle secondary" aria-expanded={problemExpanded} aria-controls="diagnostic-problem-content" onClick={()=>setProblemExpanded(value=>!value)}>{problemExpanded?'문제 접기':'문제 보기'}</button><div id="diagnostic-problem-content"><ProblemStatement key={current.problemVersion} statement={current.statement} version={current.problemVersion} api={api} manage={false}/>{current.api&&<details className="diagnostic-api-guide"><summary>제출 방식 · 제공된 구동 코드</summary><p>선택한 언어의 UserSolution 템플릿을 완성해요. 입력 처리와 구동 코드는 서버가 제공하며, 한 케이스의 호출은 같은 객체를 사용합니다.</p>{language==='CPP'&&<p>API의 long은 long long, String은 std::string, boolean은 bool, 배열은 std::vector로 구현해요. 제공된 템플릿의 함수 이름과 반환형을 유지해 주세요.</p>}{language==='PYTHON'&&<p>UserSolution 클래스의 메서드를 구현해요. 배열은 list, 문자열은 str, 논리값은 bool이며 정수 범위는 API의 int·long 기준을 따릅니다.</p>}<pre>{callableLanguage(current.api,language)?.driver}</pre></details>}{!/^## (?:예제 1|공개 호출 예제)\s*$/m.test(current.statement||'')&&<Examples examples={current.examples?.length?current.examples:[{input:current.sampleInput,output:current.sampleOutput}]}/>}</div></article>
        <ResizeHandle className="diagnostic-resizer" label={layout==='columns'?'첫 번째와 두 번째 패널 비율':'진단 문제와 편집기 비율'} value={layout==='columns'?firstColumn:mirrored?100-size.ratio:size.ratio} min={mirrored&&layout==='default'?30:20} max={mirrored&&layout==='default'?80:70} step={2} scale={columnScale} onChange={ratio=>layout==='columns'?setFirstColumn(ratio):changeSize({ratio:mirrored?100-ratio:ratio})}/>
        <div className="diagnostic-code-column"><div className="code-top-controls"><div className="code-heading"><div className="code-caption"><span className="code-filename">{callableLanguage(current.api,language)?.sourceFile||languageInfo[language].file}</span><span className="muted code-save-note" title={`${saveNote||'초안은 자동 저장돼요.'} 진단 중에는 해설과 AI 힌트를 제공하지 않습니다.`}><span aria-live="polite">{saveNote||'초안은 자동 저장돼요.'}</span> 진단 중에는 해설과 AI 힌트를 제공하지 않습니다.</span></div><div className="code-tools"><label className="language-choice">언어<SelectControl aria-label="진단 언어" title="언어를 바꿔도 제출 횟수는 유지돼요." value={language} disabled={disabled||!!item.pending} onChange={e=>changeLanguage(e.target.value)}>{(current.languages||[languageInfo.JAVA]).map(l=><option key={l.id} value={l.id}>{l.label}</option>)}</SelectControl></label>
        <EditorTools id="diagnostic-editor-tools"><summary>도구</summary><div className="tool-pop-panel"><label className="check-row vim-toggle"><input type="checkbox" checked={vim} onChange={e=>setVim(e.target.checked)}/>Vim 모드 <small>Esc 일반 모드 · i 입력 모드</small></label><EditorShortcutHelp diagnostic/></div></EditorTools>
        <ResetCode disabled={disabled||!!item.pending} onReset={()=>edit(callableLanguage(current?.api,language)?.template||(language==='JAVA'?starter:starters[language]))}/></div></div>
        </div><SplitStack className="diagnostic-split" layout={layout} share={layout==='columns'?columnSplit:split} onChange={layout==='columns'?setColumnSplit:setSplit}
          top={<div className="diagnostic-editor"><Editor key={`${current.itemId}:${language}`} language={language} id="diagnostic-source" label={`진단 ${language==='JAVA'?'Java':languageInfo[language].label} 코드`} value={source} vim={vim} disabled={disabled} onChange={edit} onRun={()=>{if(!disabled)setRunRequest(value=>value+1);}} onSubmit={()=>{if(!disabled&&!item.pending)mutate('/api/submissions',body,true);}} onLimit={()=>setError('코드는 64 KiB 이내로 작성해 주세요.')}/></div>}
          bottom={<RunConsole callable={current.api?.api} user={user} api={api} scope={current.problemVersion} disabled={disabled} examples={current.examples?.length?current.examples:[{input:current.sampleInput,output:current.sampleOutput}]}
            body={body} runRequest={runRequest} casesRequest={casesRequest} onCaseCount={setCaseCount}
            submission={result&&result.input==null&&result.diagnosticItemId===current.itemId?result:null}/>}/>
          <div className="code-bottom-controls">{session.sourceSessionId&&<div><p className="muted">이 문제나 풀이를 이미 알고 있으면 아래에 알려 주세요. 기록 후 건너뛰며 약점이나 독립적인 실력 향상 근거로 쓰지 않습니다.</p><button className="secondary" disabled={disabled||!!item.pending} onClick={()=>mutate(`/api/diagnostics/${session.id}/items/${current.itemId}/exposure`,{},true)}>이 문제나 풀이를 본 적 있어요 · 기록 후 건너뛰기</button></div>}
          {held?<div className="editor-actions"><span className="held-note">{item?.status==='PASSED'?'통과했어요.':'정식 제출 5회를 모두 사용했어요.'} 결과를 확인한 뒤 다음으로 넘어가세요.</span>
            <button className="primary" onClick={next}>{session.current?'다음 문제':'진단 결과 보기'}</button></div>
          :<div className="editor-actions">
            <button className="secondary" disabled={disabled||!!item.pending} onClick={()=>setSheet('skip')}>건너뛰기</button>
            <button className="secondary" disabled={disabled} onClick={()=>setCasesRequest(value=>value+1)}>테스트 케이스 추가{caseCount?` (${caseCount})`:''}</button>
            <button className="secondary" disabled={disabled} onClick={()=>setRunRequest(value=>value+1)}>코드 실행</button>
            <button className="primary" disabled={disabled||!!item.pending} onClick={()=>mutate('/api/submissions',body,true)}>정식 제출 ({5-item.attempts}회 남음)</button>
          </div>}
        </div></div></div>}

      {session.status==='COMPLETED'&&!held&&<DiagnosticEvaluation onLearning={onLearning} key={`evaluation-${session.id}`} api={api} session={session} onOpen={onOpen} onGeneration={onGeneration} onRuleDraft={onRuleDraft} onAssess={otherDiagnostics} learningBlocked={sessions.some(saved=>saved.status!=='COMPLETED')} />}
      {session.status==='COMPLETED'&&!held&&<div className="diagnostic-result-tools"><button className="secondary" onClick={()=>setSheet('records')}>문항별 진행과 제출 기록</button></div>}
      <Modal open={sheet==='records'} title="진단 기록" className="diagnostic-dialog" onClose={()=>setSheet(null)} wide><div className="diagnostic-dialog-content"><h4>문항별 진행과 제출 기록</h4>{recordsView}{session.status!=='COMPLETED'&&<DiagnosticEvaluation onLearning={onLearning} key={`evaluation-${session.id}`} api={api} session={session} onOpen={onOpen} onGeneration={onGeneration} onRuleDraft={onRuleDraft} onAssess={otherDiagnostics} learningBlocked={sessions.some(saved=>saved.status!=='COMPLETED')} />}</div></Modal>
      {session.status==='COMPLETED'&&!held&&<DiagnosticReassessment key={`reassessment-${session.id}`} api={api} session={session} busy={busy||!!request} onStart={mutate}/> }

    </>}
    <Modal open={sheet==='skip'} title="건너뛰는 이유" className="diagnostic-dialog" onClose={()=>{if(!busy&&!request)setSheet(null);}}>
      <div className="diagnostic-dialog-content"><p>지금 풀지 않는 이유를 남겨 주세요. 채점 결과와 별도로 저장하고, 다음 연습을 제안할 때 참고해요.</p>
      <div className="diagnostic-skip-options">{['NOT_SURE','NO_TIME','OTHER'].map(reason=><button className="secondary" key={reason} disabled={disabled||!!item?.pending} onClick={async()=>{await mutate(`/api/diagnostics/${session.id}/items/${current.itemId}/skip`,{reason},true);setSheet(null);}}>{skipReasons[reason]}</button>)}</div>
      <p className="muted">접근 어려움은 본인이 보고한 보완 후보예요. 건너뛰기만으로 약점을 확정하지 않습니다.</p></div>
    </Modal>
    <Modal open={sheet==='history'} title="지난 진단" className="diagnostic-dialog diagnostic-history-dialog" onClose={()=>setSheet(null)} wide>
      <div className="diagnostic-dialog-content">
      <p className="muted">최근 {sessions.length}개의 진단이에요. 이전 결과를 열어도 진행 중인 진단은 유지됩니다.</p>
      <ul className="diagnostic-session-list">{sessions.slice((historyPage-1)*10,historyPage*10).map(saved=><li key={saved.id}><button aria-label={`${saved.items.length}문항 · ${saved.status==='COMPLETED'?'완료':'이어서 보기'}`} onClick={()=>selectHistory(saved)}><span><strong>{bankTitle(saved.bankId||'')}</strong><small>{[...new Set(saved.items.map(i=>categories[i.category]||i.category))].join(' · ')}</small><small>{saved.createdAt?new Date(saved.createdAt).toLocaleString('ko-KR'):`기록 ${saved.id.slice(0,8)}`}</small></span><span>{saved.items.length}문항 · {saved.status==='COMPLETED'?'완료':'이어서 보기'}<small>통과 {saved.items.filter(i=>i.status==='PASSED').length} · 건너뜀 {saved.items.filter(i=>i.status==='SKIPPED').length}</small></span></button></li>)}</ul>
      <ListPagination page={historyPage} pages={Math.max(1,Math.ceil(sessions.length/10))} onChange={setHistoryPage} label="지난 진단 페이지"/></div>
    </Modal>
  </section>;
}
