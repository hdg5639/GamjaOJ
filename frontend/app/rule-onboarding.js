'use client';

import {useEffect,useRef,useState} from 'react';

const labels={QUEUED:'대기',AUTHORING:'규칙·코드 작성 중',AUTHORED:'독립 검증 코드 준비',ORACLE:'독립 검증 코드 작성 중',QUALIFYING:'실행 검증 중',ACTIVE:'등록 완료',HELD:'검증 보류',FAILED:'등록 실패',CANCELLED:'취소됨',DEADLINE_EXCEEDED:'처리 기한 초과'};
const running=['QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING'];
const reasons={REFERENCE_ORACLE_DISAGREEMENT:'정답 코드와 독립 검증 코드의 결과가 달랐어요.',MUTANT_SURVIVED:'일부러 틀리게 만든 코드를 작은 입력으로 걸러내지 못했어요.',DUPLICATE_RULE_CONTRACT:'이미 등록된 규칙과 같아요.',ONBOARDING_BUDGET_CAP:'이 요청의 예산 한도를 넘었어요.',MONTHLY_BUDGET_EXHAUSTED:'이번 달 AI 예산이 부족해요.',STRESS_RESOURCE_MARGIN:'최대 입력에서 실행 시간 기준을 넘었어요.',ONBOARDING_DEADLINE_EXCEEDED:'처리 기한 안에 마치지 못했어요.'};

export default function RuleOnboarding({api,onRegistered}) {
  const [enabled,setEnabled]=useState(null),[items,setItems]=useState([]),[rules,setRules]=useState([]);
  const [text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const pending=useRef(null),live=useRef(false),known=useRef(new Set());
  async function refresh(){
    try{
      const [list,mine]=await Promise.all([api('/api/rules/onboarding'),api('/api/rules/mine')]);
      if(!live.current)return;setItems(list);setRules(mine);
      const active=list.filter(i=>i.status==='ACTIVE').map(i=>i.id);
      if(active.some(id=>!known.current.has(id))&&known.current.size>0)onRegistered?.();
      known.current=new Set(['seen',...active]);
    }catch(e){if(live.current)setError(e.message);}
  }
  useEffect(()=>{live.current=true;
    api('/api/rules/onboarding/options').then(v=>{if(live.current)setEnabled(!!v.enabled);}).catch(()=>{if(live.current)setEnabled(false);});
    refresh();return()=>{live.current=false;};},[]);
  const busyWork=items.some(i=>running.includes(i.status));
  useEffect(()=>{if(!busyWork)return;const timer=setInterval(refresh,5000);return()=>clearInterval(timer);},[busyWork]);
  async function submit(event){
    event.preventDefault();if(busy)return;
    pending.current ||= {key:crypto.randomUUID(),request:text.trim()};
    setBusy(true);setError('');
    try{await api('/api/rules/onboarding',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},body:JSON.stringify({request:pending.current.request})});
      pending.current=null;setText('');await refresh();}
    catch(e){if(e.status>=400&&e.status<500)pending.current=null;setError(e.message);}
    finally{setBusy(false);}
  }
  async function act(path,options){
    if(busy)return;setBusy(true);setError('');
    try{await api(path,options);await refresh();onRegistered?.();}catch(e){setError(e.message);}finally{setBusy(false);}
  }
  return <section className="rule-onboarding" aria-labelledby="rule-onboarding-heading">
    <h3 id="rule-onboarding-heading">새 규칙 등록 · 실험</h3>
    <p className="draft-help">목록에 없는 알고리즘 유형을 설명하면 AI가 규칙·정답 코드·검증 자료를 만들고, 별도로 작성한 완전탐색 검증 코드와 실행 검증을 모두 통과한 경우에만 내 규칙으로 등록합니다. 요청당 AI 예산은 최대 $1, 처리 기한은 20분이며 실패해도 자동으로 다시 시도하지 않습니다.</p>
    {enabled===false&&<p className="notice">지금은 새 규칙을 등록할 수 없어요.</p>}
    {error&&<p className="notice error" role="alert">{error}</p>}
    <form onSubmit={submit}>
      <label className="field">만들고 싶은 규칙<textarea rows={4} maxLength={1000} value={text} disabled={busy||!enabled||!!pending.current} onChange={e=>setText(e.target.value)} placeholder="예: 구간 합 질의를 빠르게 처리하는 누적 합 문제"/></label>
      <button className="secondary" disabled={busy||!enabled||busyWork||(!pending.current&&text.trim().length<10)}>{pending.current?'같은 요청 다시 확인':'이 설명으로 규칙 등록 요청'}</button>
      {busyWork&&<p className="draft-help" role="status">진행 중인 등록이 끝나면 새로 요청할 수 있어요.</p>}
    </form>
    {items.length>0&&<ul className="rule-onboarding-list">{items.map(item=><li key={item.id}>
      <p><strong>{labels[item.status]||item.status}</strong> · {item.label||item.request.slice(0,60)}</p>
      {item.status==='QUALIFYING'&&<p className="draft-help">실행 검증 {Object.values(item.checks||{}).filter(v=>['AC','OK','WA'].includes(v)).length}건 완료</p>}
      {['HELD','FAILED','DEADLINE_EXCEEDED'].includes(item.status)&&<p className="draft-help">{reasons[item.error]||'검증 조건을 충족하지 못해 등록하지 않았어요.'} 사용한 AI 비용: ${Number(item.spentUsd||0).toFixed(3)}</p>}
      {running.includes(item.status)&&<button className="secondary" disabled={busy} onClick={()=>act(`/api/rules/onboarding/${item.id}/cancel`,{method:'POST'})}>이 등록 취소</button>}
    </li>)}</ul>}
    {rules.length>0&&<><h4>내가 등록한 규칙</h4><ul className="rule-onboarding-list">{rules.map(rule=><li key={rule.id}>
      <p><strong>{rule.label}</strong> · {rule.category} · {rule.status==='ACTIVE'?(rule.shared?'다른 회원에게 공개':'나만 사용'):'사용 중지'}</p>
      {rule.status==='ACTIVE'&&<button className="secondary" disabled={busy} onClick={()=>act(`/api/rules/${rule.id}/sharing`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({shared:!rule.shared})})}>{rule.shared?'공개 해제':'다른 회원에게 공개'}</button>}
    </li>)}</ul></>}
  </section>;
}
