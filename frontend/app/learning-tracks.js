'use client';
import {useEffect,useRef,useState} from 'react';
import Pager,{usePage} from './pager';
import DiagnosticPlan from './diagnostic-plan';
import {bankTitle,categoryLabels} from './diagnostic-categories';
const done=plan=>['AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(plan.status);
const labels={READY:'연습 준비',ACTIVE:'문제 풀이 중',TRAINING_ENDED:'훈련 종료 · 확인 필요',AC_WITH_HELP:'훈련 확인 완료 · 도움 사용',SELF_REPORTED_UNASSISTED_AC:'훈련 확인 완료 · 혼자 해결',HELD:'근거·문제 확인 대기',NEEDS_REVIEW:'정정 의견 확인 필요'};
export default function LearningTracks({api,userId,visible,initialEvaluation,onOpen,onGeneration,onDiagnostic,locked}) {
 const [tracks,setTracks]=useState([]),[selected,setSelected]=useState(''),[loaded,setLoaded]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false),[pending,setPending]=useState(null),[manual,setManual]=useState(null);
 const lock=useRef(false),revision=useRef(0);const storageKey=`gamjaoj-learning-action-${userId}`;
 useEffect(()=>{try{const saved=JSON.parse(sessionStorage.getItem(storageKey));if(saved?.id&&['start','reflect','next-round'].includes(saved.kind)&&saved.body)setPending(saved);}catch{}},[storageKey]);
 useEffect(()=>{if(initialEvaluation)setSelected(initialEvaluation);},[initialEvaluation]);
 async function refresh(){const request=++revision.current;try{const values=await api('/api/learning-curricula');if(request===revision.current){setTracks(Array.isArray(values)?values:[]);setLoaded(true);}}catch(e){if(request===revision.current){setError(e.message);setLoaded(true);}}}
 const waiting=tracks.some(t=>t.steps.some(s=>s.plan.status==='ACTIVE'||s.progress?.pending>0||['QUEUED','GENERATING','DESIGNING','BUILDING','VALIDATING','AWAITING_REVIEW','REVIEWING'].includes(s.plan.generationStatus)));
 useEffect(()=>{if(!visible)return;refresh();window.addEventListener('gamjaoj-plan-changed',refresh);window.addEventListener('gamjaoj-training-changed',refresh);window.addEventListener('focus',refresh);
  const timer=waiting?setInterval(refresh,5000):null;return()=>{revision.current++;clearInterval(timer);window.removeEventListener('gamjaoj-plan-changed',refresh);window.removeEventListener('gamjaoj-training-changed',refresh);window.removeEventListener('focus',refresh);};},[visible,waiting]);
 const track=tracks.find(t=>t.evaluationId===selected)||tracks[0];
 const steps=track?.steps||[],completed=steps.filter(s=>done(s.plan)).length;
 const next=steps.find(s=>s.plan.status==='ACTIVE')||steps.find(s=>s.plan.status==='TRAINING_ENDED')||steps.find(s=>s.plan.status==='READY')||steps.find(s=>s.plan.status==='NEEDS_REVIEW');
 const paging=usePage(steps,5);
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
    attempt={id:step.plan.id,kind,body};setPending(attempt);try{sessionStorage.setItem(storageKey,JSON.stringify(attempt));}catch{}
   }
   const saved=await api(`/api/diagnostic-plans/${attempt.id}/${attempt.kind}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(attempt.body)});
   setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}
   window.dispatchEvent(new Event('gamjaoj-plan-changed'));await refresh();
   if(attempt.kind==='start')await onOpen(saved.problemVersion);
  }catch(e){setError(e.message);if(e.status>=400&&e.status<500){setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}}}finally{lock.current=false;setBusy(false);}
 }
 function primary(step){const plan=step.plan;
  if(plan.status==='ACTIVE')return <button className="primary" disabled={busy||locked||!!pending} onClick={async()=>{try{await onOpen(plan.problemVersion);}catch(e){setError(e.message);}}}>이어서 학습하기</button>;
  if(plan.status==='READY'&&plan.generationId&&!step.candidate)return <button className="primary" onClick={onGeneration}>문제 생성 진행 확인</button>;
  if(plan.status==='READY'&&step.candidate)return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>act(step,'start',{problemVersion:step.candidate.version})}>바로 훈련 시작</button>;
  if(plan.status==='TRAINING_ENDED'){
   if(step.progress?.pending>0)return <p role="status">채점이 끝나면 학습 확인을 남길 수 있어요.</p>;
   if(step.progress?.latestVerdict==='AC')return <div className="learning-reflection"><p>이번 훈련은 정답으로 마쳤어요. 도움 사용 여부를 남겨 목표를 마무리하세요.</p><div><button className="primary" disabled={busy||!!pending} onClick={()=>act(step,'reflect',{usedHelp:false})}>혼자 해결했어요</button><button className="secondary" disabled={busy||!!pending} onClick={()=>act(step,'reflect',{usedHelp:true})}>도움을 받았어요</button></div></div>;
   return <button className="primary" disabled={busy||locked||!!pending} onClick={()=>act(step,'next-round')}>같은 목표로 다시 연습</button>;
  }
  if(done(plan))return <button className="secondary" disabled={busy||locked||!!pending} onClick={()=>act(step,'next-round')}>다음 회차 준비</button>;
  return <button className="secondary" disabled={plan.status==='HELD'} onClick={()=>showManual(plan.id)}>목표·문제 직접 설정</button>;
 }
 return <section className="learning-tracks" aria-label="나의 학습 계획"><header className="training-section-heading"><div><h2>나의 학습 계획</h2><p className="muted">진단에서 세운 목표를 한 단계씩 연습하고, 다음 학습으로 이어가요.</p></div><button className="secondary" onClick={()=>onDiagnostic(track?.diagnosticSessionId)}>진단·수동 계획 만들기</button></header>
  {error&&<p className="notice error" role="alert">{error} <button className="secondary" onClick={()=>{setError('');refresh();}}>다시 불러오기</button></p>}
  {pending&&<p className="notice">접수 결과를 확인하지 못한 요청이 있어요. <button className="secondary" disabled={busy||locked} onClick={()=>act()}>같은 학습 요청 다시 확인</button></p>}
  {!loaded&&<p role="status">학습 계획을 불러오는 중…</p>}
  {loaded&&!error&&!tracks.length&&<div className="learning-plan-empty"><h3>진단에서 찾은 보완점을 연습으로 이어가세요</h3><p>진단 결과의 ‘맞춤 계획 한 번에 만들기’를 누르면 목표와 진행 상황이 여기에 모여요. 기존 수동 계획도 함께 표시합니다.</p><button className="primary" onClick={()=>onDiagnostic()}>진단 결과에서 계획 만들기</button></div>}
  {track&&<>
   {track.manualReviewCount>0&&<p className="notice">정정 의견이 있는 제안 {track.manualReviewCount}개는 자동 계획에서 제외했어요. 진단 결과에서 의견을 확인하고 수동으로 목표를 정할 수 있어요.</p>}
   <div className="learning-track-choice"><label>학습 계획 선택<select aria-label="학습 계획 선택" value={track.evaluationId} onChange={e=>{setSelected(e.target.value);paging.setPage(0);setManual(null);}}>{tracks.map(t=><option key={t.evaluationId} value={t.evaluationId}>{bankTitle(t.bankId)} · {new Date(t.createdAt).toLocaleDateString('ko-KR')} · 목표 {t.steps.length}개</option>)}</select></label>
    <div className="learning-progress"><strong>{completed} / {steps.length} 목표 훈련 확인 완료</strong><progress aria-label="계획 목표 진행도" value={completed} max={steps.length||1}/><small>훈련 종료 후 학습 확인까지 남긴 목표예요. 숙련도 점수는 아니에요.</small></div></div>
   {next&&<section className="learning-next-step" aria-label="다음 학습"><span className="roadmap-kind">{next.plan.status==='ACTIVE'?'지금 이어갈 목표':'다음으로 할 일'}</span><h3>{next.plan.goal||'최신 의견 확인하기'}</h3>
    <p>{next.plan.status==='ACTIVE'?next.problemTitle:next.candidate?`연습할 문제 · ${next.candidate.title}`:labels[next.plan.status]}</p>{primary(next)}
    {next.plan.status==='READY'&&next.candidate&&<p className="draft-help">같은 분야의 연습 후보입니다. 목표와 같은 풀이 규칙을 보장하지 않으며, 문제를 읽고 적합성을 확인해 주세요.</p>}
   </section>}
   {!next&&steps.length>0&&completed===steps.length&&<p className="notice">이 계획의 목표를 모두 확인했어요. 다른 문제로 다시 연습하거나 새 진단으로 확인해 보세요.</p>}
   <ol className="learning-plan-steps" start={paging.offset+1}>{paging.visible.map(step=><li key={step.plan.id} data-status={step.plan.status}><div className="learning-goal-summary"><span className="learning-goal-number">{steps.indexOf(step)+1}</span><div><h3>{step.plan.goal||'근거 확인 후 표시할 목표'}</h3><p>{categoryLabels[step.category]||'개별 연습 목표'} · {step.basis==='SELF_REPORT'?'접근 어려움 · 본인 표시':'진단 코드 관찰'} · {step.plan.roundNumber||1}회차</p></div><strong>{labels[step.plan.status]||'확인 중'}</strong></div>
    <div className="learning-goal-actions">{step!==next&&primary(step)}<button className="secondary" disabled={step.plan.status==='HELD'} aria-expanded={manual===step.plan.id} onClick={()=>manual===step.plan.id?setManual(null):showManual(step.plan.id)}>수동 설정·근거 확인</button>
     {step.plan.sessionId&&<button className="secondary" onClick={()=>window.dispatchEvent(new CustomEvent('gamjaoj-training-open',{detail:step.plan.sessionId}))}>훈련 상세 기록</button>}</div>
    {step.progress&&<p className="muted">이번 회차 · 정식 제출 {step.progress.submissions}회 · 정답 {step.progress.accepted}회{step.progress.pending>0?` · 처리 중 ${step.progress.pending}개`:''}</p>}
    {manual===step.plan.id&&<div className="learning-manual-plan" id={`learning-manual-${step.plan.id}`} tabIndex={-1}><p>기존 수동 계획 기능으로 목표를 확인하고, 다른 문제나 생성 규칙을 직접 고를 수 있어요.</p><DiagnosticPlan api={api} row={{id:step.plan.evaluationId}} index={step.plan.observationIndex} sourceKind={step.plan.sourceKind||'CODE_OBSERVATION'} onOpen={onOpen} onGeneration={onGeneration}/></div>}
   </li>)}</ol><Pager paging={paging} label="학습 목표 페이지"/>
  </>}
 </section>;
}
