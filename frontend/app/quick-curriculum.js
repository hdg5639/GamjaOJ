'use client';
import {useEffect,useRef,useState} from 'react';
export default function QuickCurriculum({api,row,onLearning,blocked=false}) {
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[pending,setPending]=useState(null);const lock=useRef(false);
 const storageKey=`gamjaoj-curriculum-request-${row.id}`;
 useEffect(()=>{try{const saved=JSON.parse(sessionStorage.getItem(storageKey));if(saved?.key&&saved.body?.evaluationId===row.id)setPending(saved);}catch{}},[storageKey,row.id]);
 const basics=(row.facts?.items||[]).some(item=>item.skipReason==='NOT_SURE'&&!item.externallySeen);
 const observations=row.interpretation?.observations||[];
 const eligible=basics||observations.some((o,index)=>['RISK','WATCH'].includes(o.tone)&&o.confidence==='SUPPORTED'&&o.nextAction==='PRACTICE'&&!(row.corrections||[]).some(c=>c.observationIndex===index));
 const available=!blocked&&row.facts?.complete&&!['STALE_EXPOSURE','HELD_REVIEW','HIDDEN_DURING_ASSESSMENT'].includes(row.status);
 async function create(){if(lock.current||!available)return;lock.current=true;setBusy(true);setError('');
  const attempt=pending||{key:crypto.randomUUID(),body:{evaluationId:row.id}};setPending(attempt);try{sessionStorage.setItem(storageKey,JSON.stringify(attempt));}catch{}
  try{await api('/api/learning-curricula',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':attempt.key},body:JSON.stringify(attempt.body)});
   setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}window.dispatchEvent(new Event('gamjaoj-plan-changed'));onLearning(row.id);
  }catch(e){setError(e.message);if(e.status>=400&&e.status<500){setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}}}finally{lock.current=false;setBusy(false);}
 }
 if(!available)return null;
 return <section className="quick-curriculum" aria-label="맞춤 계획 만들기"><div><h3>진단 다음은, 나에게 맞는 연습</h3><p>접근이 어려웠던 분야와 코드에서 확인한 보완점을 학습 목표로 묶어요. 목표 저장에는 추가 AI 호출이 없어요.</p>
  {!eligible&&!pending&&<p className="muted">자동으로 묶을 보완 목표가 없어요. 아래에서 목표를 직접 정하거나 다른 분야를 진단할 수 있어요.</p>}</div>
  <button className="primary" disabled={busy||(!eligible&&!pending)} onClick={create}>{busy?'계획을 준비하고 있어요…':pending?'같은 맞춤 계획 요청 다시 확인':'맞춤 계획 한 번에 만들기'}</button>
  <button className="secondary" onClick={()=>onLearning(row.id)}>저장한 계획 보기</button>
  {error&&<p role="alert" className="notice error">{error}</p>}
 </section>;
}
