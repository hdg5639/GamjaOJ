'use client';
import {useRef,useState} from 'react';
import {categoryLabels as labels} from './diagnostic-categories';
export default function DiagnosticReassessment({api,session,busy,onStart}) {
  const [banks,setBanks]=useState(null),[scope,setScope]=useState({}),[loading,setLoading]=useState(false),[error,setError]=useState(''),[trained,setTrained]=useState([]);
  const lock=useRef(false);
  async function load(){if(lock.current)return;lock.current=true;setLoading(true);setError('');
    try{const [available,categories]=await Promise.all([api(`/api/diagnostics/${session.id}/reassessments`),api(`/api/diagnostic-plans/trained-scope?sourceSessionId=${session.id}`)]);setBanks(available);setTrained(categories);setScope({});}
    catch(e){setError(e.message);}finally{lock.current=false;setLoading(false);}}
  return <div className="diagnostic-plan">
    <h2>다른 문제로 재평가</h2>
    <p>원래 진단에 포함된 분야 중 대응 문항이 준비된 분야를 선택합니다. 재평가는 선택이며 실력 향상을 자동으로 판정하지 않습니다.</p>
    <button className="secondary" disabled={busy||loading} onClick={load}>재평가 가능한 분야 확인</button>
    {error&&<p role="alert">{error}</p>}
    {banks?.length===0&&<p className="notice">지금 시작할 수 있는 재평가 문항이 없습니다. 검토가 끝나지 않았거나 이미 배정된 문항일 수 있어요. 일반 연습은 계속할 수 있습니다.</p>}
    {banks?.map(bank=><fieldset key={bank.id} disabled={busy||loading}><legend>{bank.id.startsWith('core-b-')?'핵심 시범 재평가 B':`재평가 · ${bank.id}`}</legend>
      <p>난이도는 잠정 분류입니다. 이전 진단과 규칙·표현이 달라질 수 있으며, 결과 차이만으로 실력 향상을 단정하지 않습니다.</p>
      {bank.categories.some(c=>trained.includes(c))&&<div><p>정답·도움 사용 기록을 남긴 훈련의 원래 진단 분야: {bank.categories.filter(c=>trained.includes(c)).map(c=>labels[c]||c).join(', ')}. 수정한 목표와 연습 문제의 적합성이나 숙련도를 인증하는 추천은 아닙니다.</p><button className="secondary" onClick={()=>setScope({...scope,[bank.id]:bank.categories.filter(c=>trained.includes(c))})}>훈련에 연결된 분야 선택</button></div>}
      {bank.categories.map(c=><label key={c}><input type="checkbox" checked={(scope[bank.id]||[]).includes(c)} onChange={e=>setScope({...scope,[bank.id]:e.target.checked?[...(scope[bank.id]||[]),c]:(scope[bank.id]||[]).filter(x=>x!==c)})}/>{labels[c]||c} · 하·중 2문항</label>)}
      <p className="muted">문제나 풀이를 이전에 본 적 있다면 문항 화면에서 알려 주세요. 해당 문항은 평가 근거에서 제외하고 건너뜁니다.</p>
      <button className="primary" disabled={!(scope[bank.id]||[]).length} onClick={()=>onStart(`/api/diagnostics/${session.id}/reassessments`,{bankId:bank.id,categories:scope[bank.id]},true)}>선택한 {(scope[bank.id]||[]).length*2}문항 재평가 시작</button>
    </fieldset>)}
  </div>;
}
