'use client';
import FollowupGoal from './followup-goal';
import { useEffect, useState } from 'react';

const states = {QUEUED:'분석 대기',RUNNING:'분석 중',HELD_DISABLED:'AI 호출이 일시 중지되어 있어요.',HELD_BUDGET:'서비스 전체 API 예산이 부족해 보류했어요.',UNKNOWN:'응답을 확인하지 못했어요. 재시도는 추가 비용이 발생할 수 있어요.',FAILED:'분석을 완료하지 못했어요.',COMPLETED:'분석 완료'};
export default function AiFeedback({ submission, api }) {
  const [items,setItems]=useState([]),[question,setQuestion]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [operator,setOperator]=useState(false);
  useEffect(()=>{let stopped=false;
    const refresh=()=>api(`/api/ai/tasks?submissionId=${submission.id}`).then(data=>{if(!stopped)setItems(data);}).catch(e=>{if(!stopped)setError(e.message);});
    refresh();api('/api/ai/status').then(data=>{if(!stopped)setOperator(!!data.operator);}).catch(()=>{});
    window.addEventListener('focus',refresh);
    const timer=items.some(item=>['QUEUED','RUNNING'].includes(item.status))?setInterval(refresh,5000):null;
    return()=>{stopped=true;if(timer)clearInterval(timer);window.removeEventListener('focus',refresh);};
  },[submission.id,items.some(item=>['QUEUED','RUNNING'].includes(item.status))]);
  async function request(kind,strong=false) {
    setBusy(true);setError('');
    try {await api('/api/ai/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({submissionId:submission.id,kind,question:kind==='HINT'?question:'',strong})});setItems(await api(`/api/ai/tasks?submissionId=${submission.id}`));}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  async function retry(id) {
    setBusy(true);setError('');try {await api(`/api/ai/tasks/${id}/retry`,{method:'POST'});setItems(await api(`/api/ai/tasks?submissionId=${submission.id}`));}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  const held=submission.problemHeld||items.some(item=>item.problemHeld);
  if(submission.status!=='FINISHED'||submission.verdict==='IE')return null;
  return <section className="ai-feedback" aria-label="개인 학습 피드백">
    <h4>개인 학습 피드백</h4>{held&&<p className="notice">문제 검토 중 · 이전 피드백은 참고 기록이며 학습 판단과 다음 출제의 근거에서 제외됩니다. 새 분석은 보류됩니다.</p>}<p className="draft-help">저장된 이 제출을 분석해요. 학습 피드백은 채점 판정을 바꾸지 않으며, 같은 분석은 재사용합니다.</p>
    <div className="editor-tools"><button className="secondary" disabled={busy||held} onClick={()=>request('ANALYSIS')}>풀이 분석 요청</button>
      {operator&&<button className="secondary" disabled={busy||held} onClick={()=>request('ANALYSIS',true)}>상위 모델로 재분석</button>}</div>
    <label>추가로 궁금한 점<textarea rows={2} value={question} maxLength={1000} onChange={e=>setQuestion(e.target.value)} placeholder="이 코드에서 합계가 틀리는 이유가 궁금해요" /></label>
    <button className="secondary" disabled={busy||held||!question.trim()} onClick={()=>request('HINT')}>맞춤 힌트 요청</button>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {items.map(item=><article key={item.id}><h4>{item.kind==='HINT'?'맞춤 힌트':'풀이 분석'} · {item.problemHeld&&item.status!=='COMPLETED'?'문제 검토 중 · 분석 보류':states[item.status]||item.status}</h4>
      <p className="draft-help">{item.model} · {item.effort}{item.errorCode&&` · ${item.errorCode}`}</p>
      {item.result&&<><p>{item.result.summary}</p><ul>{item.result.observations.map((text,i)=><li key={i}>{text}</li>)}</ul><h4>다음에 해볼 것</h4><ul>{item.result.nextSteps.map((text,i)=><li key={i}>{text}</li>)}</ul><p className="muted">{item.result.uncertainty}</p></>}
      {item.kind==='ANALYSIS'&&item.status==='COMPLETED'&&item.result&&<FollowupGoal analysis={item} api={api} disabled={held||busy}/>}
      {['FAILED','UNKNOWN'].includes(item.status)&&<button className="secondary" disabled={busy||held} onClick={()=>retry(item.id)}>추가 비용으로 재시도</button>}
    </article>)}
  </section>;
}
