'use client';
import {useEffect,useRef,useState} from 'react';
import Pager,{usePage} from './pager';
import DiagnosticPlan from './diagnostic-plan';
import Modal from './modal';
import ThinkingDifficulty from './thinking-difficulty';
import {bankTitle,categoryLabels} from './diagnostic-categories';
const done=plan=>['AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(plan.status);
const preparing=new Set(['WAITING','GENERATING']);
const labels={READY:'연습 준비',ACTIVE:'문제 풀이 중',TRAINING_ENDED:'훈련 종료 · 확인 필요',AC_WITH_HELP:'훈련 확인 완료 · 도움 사용',SELF_REPORTED_UNASSISTED_AC:'훈련 확인 완료 · 혼자 해결',HELD:'근거·문제 확인 대기',NEEDS_REVIEW:'정정 의견 확인 필요'};
export default function LearningTracks({api,userId,visible,initialEvaluation,onOpen,onGeneration,onDiagnostic,locked,sessions=[],problems=[],onToolsHost,onSessionsChange=()=>{}}) {
 const [tracks,setTracks]=useState([]),[selected,setSelected]=useState(''),[loaded,setLoaded]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false),[pending,setPending]=useState(null),[manual,setManual]=useState(null),[focused,setFocused]=useState(''),[switching,setSwitching]=useState(null),[switchNote,setSwitchNote]=useState('');
 const lock=useRef(false),revision=useRef(0),prepared=useRef(new Set());const storageKey=`gamjaoj-learning-action-${userId}`;
 useEffect(()=>{try{const saved=JSON.parse(sessionStorage.getItem(storageKey));if(saved?.id&&['start','reflect','next-round','switch'].includes(saved.kind)&&saved.body)setPending(saved);}catch{}},[storageKey]);
 useEffect(()=>{if(initialEvaluation)setSelected(initialEvaluation);},[initialEvaluation]);
 async function refresh(){const request=++revision.current;try{const values=await api('/api/learning-curricula');if(request===revision.current){setTracks(Array.isArray(values)?values:[]);setLoaded(true);}}catch(e){if(request===revision.current){setError(e.message);setLoaded(true);}}}
 const waiting=tracks.some(t=>t.steps.some(s=>s.plan.status==='ACTIVE'||s.progress?.pending>0||preparing.has(s.preparation?.status)||['QUEUED','GENERATING','DESIGNING','BUILDING','VALIDATING','AWAITING_REVIEW','REVIEWING'].includes(s.plan.generationStatus)));
 useEffect(()=>{if(!visible)return;refresh();window.addEventListener('gamjaoj-plan-changed',refresh);window.addEventListener('gamjaoj-training-changed',refresh);window.addEventListener('focus',refresh);
  const timer=waiting?setInterval(refresh,5000):null;return()=>{revision.current++;clearInterval(timer);window.removeEventListener('gamjaoj-plan-changed',refresh);window.removeEventListener('gamjaoj-training-changed',refresh);window.removeEventListener('focus',refresh);};},[visible,waiting]);
 // Existing manual goals can opt into the same preparation path when this learning screen is opened.
 useEffect(()=>{if(!visible||locked||manual)return;
  const ready=tracks.flatMap(t=>t.steps).filter(s=>s.plan.status==='READY'&&!s.preparation&&!prepared.current.has(s.plan.id));
  for(const step of ready){prepared.current.add(step.plan.id);api(`/api/learning-curricula/plans/${step.plan.id}/prepare`,{method:'POST'}).then(refresh).catch(e=>{prepared.current.delete(step.plan.id);setError(e.message);});}
 },[visible,locked,manual,tracks]);
 async function prepare(step){setBusy(true);setError('');try{await api(`/api/learning-curricula/plans/${step.plan.id}/prepare`,{method:'POST'});await refresh();}catch(e){setError(e.message);}finally{setBusy(false);}}
 const track=tracks.find(t=>t.evaluationId===selected)||tracks[0];
 const steps=track?.steps||[],completed=steps.filter(s=>done(s.plan)).length;
 const next=steps.find(s=>s.plan.status==='ACTIVE')||steps.find(s=>s.plan.status==='TRAINING_ENDED')||steps.find(s=>s.plan.status==='READY')||steps.find(s=>s.plan.status==='NEEDS_REVIEW');
 const paging=usePage(steps,5);
 const active=sessions.find(s=>s.status==='ACTIVE');
 const chosen=steps.find(s=>s.plan.id===focused)||next||steps[0];
 const phase=chosen?.plan.status==='ACTIVE'?2:chosen&&['TRAINING_ENDED','AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(chosen.plan.status)?3:1;
 const activeStep=tracks.flatMap(t=>t.steps).find(s=>s.plan.sessionId===active?.id&&s.plan.status==='ACTIVE');
 const problemName=step=>step?.problemTitle||step?.candidate?.title||`${categoryLabels[step?.category]||'목표별'} 맞춤 문제`;
 const stage=step=>step.plan.status==='READY'?(step.candidate?'시작 가능':step.preparation?.status==='FAILED'?'준비 확인 필요':'문제 준비 중'):labels[step.plan.status];
 function selectStep(step){setFocused(step.plan.id);requestAnimationFrame(()=>{
  const pane=document.querySelector(window.matchMedia('(max-width:900px)').matches?'.learning-current-problem':'.learning-workspace-top');pane?.scrollIntoView({block:'nearest'});
  document.getElementById('learning-selected-heading')?.focus({preventScroll:true});
 });}
 function chooseSwitch(step){setSwitching(step);setSwitchNote('다른 계획 문제로 전환');}

 function showManual(id){setManual(id);const index=steps.findIndex(s=>s.plan.id===id);if(index>=0)paging.setPage(Math.floor(index/5));requestAnimationFrame(()=>{const node=document.getElementById(`learning-manual-${id}`);node?.scrollIntoView({block:'nearest'});node?.focus({preventScroll:true});});}
 async function act(step,kind,body){if(lock.current||locked)return;lock.current=true;setBusy(true);setError('');
  let attempt=pending;
  try{
   if(!attempt){
    if(kind==='next-round'){
     const options=await api(`/api/diagnostic-plans/options?evaluationId=${step.plan.evaluationId}&observationIndex=${step.plan.observationIndex}&sourceKind=${step.plan.sourceKind||'CODE_OBSERVATION'}`);
     if(options.corrections?.length){showManual(step.plan.id);throw new Error('정정 의견을 먼저 확인하고 수동 계획에서 다음 회차를 준비해 주세요.');}
     body={reviewHash:options.reviewHash};
    }
    attempt={id:step.plan.id,kind,body,...(kind==='switch'?{key:crypto.randomUUID()}: {})};setPending(attempt);try{sessionStorage.setItem(storageKey,JSON.stringify(attempt));}catch{}
   }
   const saved=await api(attempt.kind==='switch'?'/api/learning-curricula/switch':`/api/diagnostic-plans/${attempt.id}/${attempt.kind}`,{method:'POST',headers:{'Content-Type':'application/json',...(attempt.kind==='switch'?{'Idempotency-Key':attempt.key}:{})},body:JSON.stringify(attempt.body)});
   setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}
   window.dispatchEvent(new Event('gamjaoj-plan-changed'));await refresh();
   if(attempt.kind==='reflect')setFocused('');
   if(['start','switch'].includes(attempt.kind)){setFocused(saved.id);setSwitching(null);if(saved.status==='ACTIVE'&&saved.problemVersion)await onOpen(saved.problemVersion);else setError('요청은 이미 처리됐어요. 현재 훈련 상태를 확인해 주세요.');}
  }catch(e){setError(e.message);if(e.status>=400&&e.status<500){setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}try{await refresh();onSessionsChange(await api('/api/training-sessions'));}catch{}}}finally{lock.current=false;setBusy(false);}
 }
 function primary(step){const plan=step.plan;
  if(plan.status==='READY'&&step.candidate&&active&&active.id!==plan.sessionId)return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>chooseSwitch(step)}>이 문제로 전환</button>;
  if(plan.status==='TRAINING_ENDED'&&plan.problemVersion&&!(step.progress?.pending>0)&&step.progress?.latestVerdict!=='AC')return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>chooseSwitch(step)}>이 문제 다시 풀기</button>;
  if(plan.status==='ACTIVE')return <button className="primary" disabled={busy||locked||!!pending} onClick={async()=>{try{await onOpen(plan.problemVersion);}catch(e){setError(e.message);}}}>이어서 학습하기</button>;
  if(plan.status==='READY'&&!step.candidate&&(step.preparation||plan.generationId)){
   const failed=step.preparation?.status==='FAILED'||/FAILED|REJECTED|NEEDS_REVIEW/.test(plan.generationStatus||'');
   return <div className="learning-generation-state"><p role="status">{failed?'문제 준비에 확인이 필요해요.':step.preparation?.status==='WAITING'?'기존 문제를 찾고, 없으면 자동으로 생성해요. 다른 출제가 진행 중이면 차례를 기다려요.':'맞춤 문제를 자동 생성·검증하고 있어요. 준비되면 바로 훈련할 수 있어요.'}</p>{step.preparation?.message&&<p className="muted">{step.preparation.message}</p>}
    {failed&&!plan.generationId&&<button className="secondary" disabled={busy||locked} onClick={()=>prepare(step)}>문제 준비 다시 시도</button>}
    {plan.generationId&&<button className="secondary" onClick={onGeneration}>생성·검증 상세 보기</button>}</div>;
  }
  if(plan.status==='READY'&&step.candidate)return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>act(step,'start',{problemVersion:step.candidate.version})}>바로 훈련 시작</button>;
  if(plan.status==='TRAINING_ENDED'){
   if(step.progress?.pending>0)return <p role="status">채점이 끝나면 학습 확인을 남길 수 있어요.</p>;
   if(step.progress?.latestVerdict==='AC')return <div className="learning-reflection"><p>이번 훈련은 정답으로 마쳤어요. 도움 사용 여부를 남겨 목표를 마무리하세요.</p><div><button className="primary" disabled={busy||!!pending} onClick={()=>act(step,'reflect',{usedHelp:false})}>혼자 해결했어요</button><button className="secondary" disabled={busy||!!pending} onClick={()=>act(step,'reflect',{usedHelp:true})}>도움을 받았어요</button></div></div>;
   return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>act(step,'next-round')}>같은 목표로 다시 연습</button>;
  }
  if(done(plan))return <button className="secondary" disabled={busy||locked||!!pending} onClick={()=>act(step,'next-round')}>다음 회차 준비</button>;
  return <button className="secondary" disabled={plan.status==='HELD'} onClick={()=>showManual(plan.id)}>목표·문제 직접 설정</button>;
 }
 return <section className="learning-tracks" aria-label="나의 학습 계획"><header className="training-section-heading"><div><h2>나의 학습 계획</h2><p className="muted">문제를 선택하고, 풀고, 마무리하며 한 단계씩 이어가세요.</p></div><button className="secondary" onClick={()=>onDiagnostic(track?.diagnosticSessionId)}>진단·수동 계획 만들기</button></header>
  {error&&<p className="notice error" role="alert">{error} <button className="secondary" onClick={()=>{setError('');refresh();}}>다시 불러오기</button></p>}
  {pending&&<p className="notice">접수 결과를 확인하지 못한 요청이 있어요. <button className="secondary" disabled={busy||locked} onClick={()=>act()}>같은 학습 요청 다시 확인</button></p>}
  {!loaded&&<p role="status">학습 계획을 불러오는 중…</p>}
  <div className="learning-workspace-top">
   <section className="learning-plan-overview" aria-label="계획 선택과 진행도">
    {track?<><label>학습 계획 선택<select aria-label="학습 계획 선택" value={track.evaluationId} onChange={e=>{setSelected(e.target.value);setFocused('');paging.setPage(0);setManual(null);}}>{tracks.map(t=><option key={t.evaluationId} value={t.evaluationId}>{bankTitle(t.bankId)} · {new Date(t.createdAt).toLocaleDateString('ko-KR')} · 목표 {t.steps.length}개</option>)}</select></label>
     <div className="learning-progress"><strong>{completed} / {steps.length} 목표 훈련 확인 완료</strong><progress aria-label="계획 목표 진행도" value={completed} max={steps.length||1}/><small>정답 제출 후 학습 확인까지 마친 목표예요.</small></div>
     <ol className="learning-flow" aria-label="훈련 진행 순서"><li aria-current={phase===1?'step':undefined}>1. 문제 선택</li><li aria-current={phase===2?'step':undefined}>2. 풀이·제출</li><li aria-current={phase===3?'step':undefined}>3. 마무리·학습 확인</li></ol>
     <p className="muted">아래 목록에서 문제를 골라 시작하세요. 준비 중인 문제는 자동 생성·검증이 끝나면 열려요.</p>
    </>:loaded&&<div className="learning-plan-empty"><h3>아직 학습 계획이 없어요</h3><p>진단 결과에서 보완 목표를 모으거나 수동 계획을 만들어 보세요.</p><button className="primary" onClick={()=>onDiagnostic()}>진단 결과에서 계획 만들기</button></div>}
   </section>
   <section className="learning-current-problem" aria-label="다음 학습">
    <span className="roadmap-kind">{active?'현재 진행 중':'훈련 시작하기'}</span>
    {active&&<><h3>{problems.find(p=>p.version===active.problemVersion)?.title||activeStep?.problemTitle||'현재 훈련 문제'}</h3><p className="muted"><ThinkingDifficulty compact problem={problems.find(p=>p.version===active.problemVersion)}/></p><p className="learning-current-goal">{active.goal||'자유 연습'}</p><p className="muted">정식 제출 {active.submissions}회 · 정답 {active.accepted}회{active.pending>0?` · 채점 중 ${active.pending}개`:''}</p></>}
    <div className="learning-session-actions" ref={onToolsHost}/>
    {chosen&&<section className="learning-next-step" aria-label="선택한 문제"><span className="roadmap-kind">{chosen.plan.status==='ACTIVE'?'현재 목표 확인':`선택한 단계 · ${steps.indexOf(chosen)+1} / ${steps.length}`}</span><h3 id="learning-selected-heading" tabIndex={-1} className={chosen.plan.status==='ACTIVE'&&active?'sr-only':undefined}>{chosen.plan.status==='ACTIVE'&&active?'선택한 문제 정보':problemName(chosen)}</h3>{chosen.candidate&&!(chosen.plan.status==='ACTIVE'&&active)&&<p className="muted"><ThinkingDifficulty compact problem={chosen.candidate}/></p>}{!(chosen.plan.status==='ACTIVE'&&active)&&<p className="learning-selected-goal">{chosen.plan.goal||'최신 의견 확인하기'}</p>}
     <p className="learning-selected-state">{stage(chosen)} · {chosen.plan.roundNumber||1}회차</p>
     <div className="learning-selected-actions">{chosen.plan.status!=='ACTIVE'&&primary(chosen)}<button className="secondary" disabled={chosen.plan.status==='HELD'} onClick={()=>showManual(chosen.plan.id)}>수동 설정·근거 확인</button>{chosen.plan.sessionId&&<button className="secondary" onClick={()=>window.dispatchEvent(new CustomEvent('gamjaoj-training-open',{detail:chosen.plan.sessionId}))}>훈련 상세 기록</button>}</div>
     {chosen.plan.status==='READY'&&chosen.candidate&&!active&&<p className="draft-help">목표에 맞춰 연결한 문제예요. 수동 설정에서 다른 문제로 바꿀 수 있어요.</p>}
    </section>}
    {!active&&!chosen&&<p className="muted">계획 문제를 선택하거나 직접 문제와 목표를 정해 훈련을 시작하세요.</p>}
   </section>
  </div>
  {track&&<section className="learning-problem-list" aria-label="계획 문제 목록"><div className="training-section-heading"><h3>계획 문제 <span className="muted">{steps.length}개</span></h3><span className="muted">목록에서 선택 → 상단에서 시작·전환</span></div>
   {track.manualReviewCount>0&&<p className="notice">정정 의견이 있는 제안 {track.manualReviewCount}개는 자동 계획에서 제외했어요. 진단 결과에서 확인해 주세요.</p>}
   {!next&&steps.length>0&&completed===steps.length&&<p className="notice">모든 목표의 훈련을 확인했어요. 문제를 선택해 다른 회차로 연습할 수 있어요.</p>}
   <ol className="learning-plan-steps" start={paging.offset+1}>{paging.visible.map(step=><li key={step.plan.id} data-plan-id={step.plan.id} data-status={step.plan.status}><button className="learning-problem-row" aria-label={`${steps.indexOf(step)+1}단계 · ${problemName(step)}`} aria-current={chosen?.plan.id===step.plan.id?'step':undefined} onClick={()=>selectStep(step)}>
    <span className="learning-goal-number">{done(step.plan)?'✓':steps.indexOf(step)+1}</span><span className="learning-problem-copy"><strong>{problemName(step)}</strong><span>{categoryLabels[step.category]||'개별 연습'} · {step.plan.roundNumber||1}회차</span></span><span className="learning-problem-state">{stage(step)}<small>{chosen?.plan.id===step.plan.id?'선택됨':'선택 →'}</small></span>
   </button></li>)}</ol><Pager paging={paging} label="학습 목표 페이지"/>
  </section>}
  <Modal open={!!manual} title="수동 설정·근거 확인" onClose={()=>setManual(null)} className="diagnostic-dialog" wide><div className="diagnostic-dialog-content">{steps.find(s=>s.plan.id===manual)&&<div className="learning-manual-plan" id={`learning-manual-${manual}`} tabIndex={-1}><p>목표와 근거를 확인하고, 문제나 생성 규칙을 직접 고를 수 있어요.</p><DiagnosticPlan key={manual} api={api} row={{id:track.evaluationId}} index={steps.find(s=>s.plan.id===manual).plan.observationIndex} sourceKind={steps.find(s=>s.plan.id===manual).plan.sourceKind||'CODE_OBSERVATION'} onOpen={async version=>{setManual(null);await onOpen(version);}} onGeneration={()=>{setManual(null);onGeneration();}}/></div>}</div></Modal>
  <Modal open={!!switching} title="훈련 문제 전환" onClose={()=>setSwitching(null)} className="diagnostic-dialog"><div className="diagnostic-dialog-content">{switching&&<><h3>{problemName(switching)}</h3><p>{active?'현재 훈련의 제출과 코드는 그대로 남기고, 이 문제로 전환해요.':'이 문제를 새 회차로 다시 연습해요.'}</p><label>현재 훈련 마무리 메모<textarea value={switchNote} onChange={e=>setSwitchNote(e.target.value)} maxLength={2000} rows={3} disabled={busy}/></label>{error&&<p role="alert" className="notice error">{error}</p>}{pending?.kind==='switch'&&<button className="secondary" disabled={busy||locked} onClick={()=>act()}>같은 전환 요청 다시 확인</button>}<button className="primary" disabled={busy||locked||!!pending} onClick={()=>act(switching,'switch',{planId:switching.plan.id,problemVersion:switching.candidate?.version||switching.plan.problemVersion,activeSessionId:active?.id||null,note:switchNote})}>저장하고 이 문제로 전환</button></>}</div></Modal>
 </section>;
}
