'use client';
import SelectControl from './select-control';
import {useState} from 'react';
export default function FollowupGoal({analysis,api,disabled}) {
  const [options,setOptions]=useState(null),[step,setStep]=useState('0'),[focus,setFocus]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  async function load(){setBusy(true);setError('');try{const value=await api(`/api/practice-followups/options?analysisId=${analysis.id}`);setOptions(value);setFocus(value.focuses[0]?.id||'');}catch(e){setError(e.message);}finally{setBusy(false);}}
  return <div className="followup-goal">
    {!options?<button className="secondary" disabled={disabled||busy} onClick={load}>이 분석으로 다음 훈련 준비</button>:<form onSubmit={async event=>{
      event.preventDefault();if(busy)return;setBusy(true);setError('');try{
        await api('/api/practice-followups',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({analysisId:analysis.id,stepIndex:Number(step),focus})});
        window.dispatchEvent(new Event('gamjaoj-followup-created'));
      }catch(e){setError(e.message);}finally{setBusy(false);}
    }}>
      <p>분석은 학습 제안입니다. 본인에게 필요한 지점을 확인해 주세요.</p>
      <label>다시 연습할 지점<SelectControl value={step} onChange={e=>setStep(e.target.value)} disabled={busy}>{options.steps.map((text,index)=><option key={index} value={index}>{text}</option>)}</SelectControl></label>
      <label>연습 목표<SelectControl value={focus} onChange={e=>setFocus(e.target.value)} disabled={busy}>{options.focuses.map(item=><option key={item.id} value={item.id}>{item.label}</option>)}</SelectControl></label>
      <p className="draft-help">{options.type} · 확인하면 준비된 문제를 먼저 찾아요. 이 단계에서는 모델을 호출하지 않습니다.</p>
      <button className="secondary" disabled={disabled||busy||!options.steps.length||!focus}>이 목표 확인하고 다음 훈련 찾기</button>
    </form>}
    {error&&<p role="alert" className="notice error">{error}</p>}
  </div>;
}
