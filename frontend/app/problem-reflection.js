'use client';
import {useEffect,useState} from 'react';
export const confidenceLabels={SOLID:'확실히 풀 수 있음',SHAKY:'조금 애매함',REVISIT:'다시 풀어야 함'};

export default function ProblemReflection({submission,api,onSaved}){
 const [saved,setSaved]=useState(null),[note,setNote]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState('');
 useEffect(()=>{let live=true;setSaved(null);setNote('');setError('');setMessage('');
  api(`/api/my/reflections?problemVersion=${encodeURIComponent(submission.problemVersion)}`).then(data=>{if(live){setSaved(data);setNote(data.note||'');}}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};
 },[submission.id,api]);
 async function save(confidence){
  setBusy(true);setError('');setMessage('');
  try{const result=await api('/api/my/reflections',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({submissionId:submission.id,confidence,note:confidence?note:''})});setSaved(result);setNote(result.note||'');setMessage(confidence?'풀이 회고를 저장했어요.':'평가를 지웠어요.');onSaved?.();}
  catch(e){setError(e.message);}finally{setBusy(false);}
 }
 return <section className="problem-reflection" aria-label="풀이 자신감">
  <h4>다음에도 혼자 풀 수 있을까요?</h4><p className="muted">정답 여부와 별개로, 지금의 자신감을 남겨요.</p>
  <div className="confidence-options" role="group" aria-label="내 풀이 평가">{Object.entries(confidenceLabels).map(([value,label])=><button key={value} className="secondary" aria-pressed={saved?.confidence===value} disabled={!saved||busy} onClick={()=>save(value)}>{label}</button>)}</div>
  {saved?.confidence&&<><label>다음에 볼 짧은 메모<textarea rows={2} maxLength={500} value={note} disabled={busy} onChange={e=>setNote(e.target.value)} placeholder="예: 경계 조건을 다시 생각해 보기 / 더 간단한 풀이 찾아보기"/></label><div className="editor-tools"><button className="secondary" disabled={busy||note===(saved.note||'')} onClick={()=>save(saved.confidence)}>메모 저장</button><button className="secondary" disabled={busy} onClick={()=>save(null)}>평가 지우기</button></div></>}
  {saved?.submissionId&&saved.submissionId!==submission.id&&<p className="draft-help">다른 제출에 남긴 평가예요. 다시 선택하면 지금 보고 있는 코드 기준으로 저장해요.</p>}
  {busy&&<p role="status">저장 중…</p>}{message&&<p role="status">{message}</p>}{error&&<p role="alert" className="notice error">{error}</p>}
 </section>;
}
