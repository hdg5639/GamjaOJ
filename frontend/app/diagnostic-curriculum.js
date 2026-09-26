'use client';
import {useRef,useState} from 'react';
export default function DiagnosticCurriculum({api,evaluationId}) {
  const [plans,setPlans]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const lock=useRef(false),pending=useRef(null);
  async function load(){if(lock.current)return;lock.current=true;setBusy(true);setError('');try{setPlans(await api(`/api/diagnostic-plans?evaluationId=${evaluationId}`));}catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}}
  async function move(index,delta){if(lock.current)return;
    if(!pending.current){const desired=plans.map(p=>p.id);[desired[index],desired[index+delta]]=[desired[index+delta],desired[index]];pending.current={evaluationId,previous:plans.map(p=>p.id),desired};}
    lock.current=true;setBusy(true);setError('');try{
      setPlans(await api('/api/diagnostic-plans/order',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(pending.current)}));pending.current=null;
    }catch(e){if(e.status>=400&&e.status<500)pending.current=null;setError(e.message);}finally{lock.current=false;setBusy(false);}
  }
  function open(plan){const node=document.getElementById(`diagnostic-observation-${evaluationId}-${plan.observationIndex}`);node?.scrollIntoView({block:'start'});node?.focus();}
  const next=plans?.find(p=>p.status==='ACTIVE')||plans?.find(p=>p.status==='READY');
  return <section aria-label="학습 순서"><h3>내 학습 순서</h3><p>확정한 목표의 순서를 직접 정합니다. 다른 순서로 연습해도 되며, 순서 변경은 AI 호출이나 훈련을 시작하지 않습니다.</p>
    <button className="secondary" disabled={busy||!!pending.current} onClick={load}>학습 순서 불러오기</button>
    {error&&<p role="alert">{error}</p>}
    {pending.current&&<button disabled={busy} onClick={()=>move(0,0)}>같은 순서 저장 다시 확인</button>}
    {plans?.length===0&&<p>아래 관찰에서 목표를 확인하고 저장하면 학습 순서를 정할 수 있어요.</p>}
    {next&&<p className="notice">{next.status==='ACTIVE'?'진행 중인 목표':'순서상 다음 목표'}: {next.goal} <button onClick={()=>open(next)}>해당 관찰·계획으로 이동</button></p>}
    <ol>{plans?.map((plan,index)=><li key={plan.id}>{plan.goal||'확인 보류된 목표'} · {plan.roundNumber}회차
      <button className="secondary" aria-label={`${index+1}번째 목표 위로`} disabled={busy||!!pending.current||index===0} onClick={()=>move(index,-1)}>위로</button>
      <button className="secondary" aria-label={`${index+1}번째 목표 아래로`} disabled={busy||!!pending.current||index===plans.length-1} onClick={()=>move(index,1)}>아래로</button>
      <button className="secondary" onClick={()=>open(plan)}>관찰·계획 보기</button>
    </li>)}</ol>
  </section>;
}
