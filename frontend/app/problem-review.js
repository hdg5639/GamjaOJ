'use client';
import {useState} from 'react';
export default function ProblemReview({item,api,onHeld,relatedStructures=false}) {
  const [reason,setReason]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  if(item.problemHeld)return <p className="notice">문제 검토 중 · {item.reviewReason}<br/>새 풀이와 분석을 보류했어요. 기존 기록은 유지됩니다.</p>;
  return <details><summary>문제 오류 신고·풀이 보류</summary>
    <p>이 문제의 새 실행·제출·훈련·분석을 막고, 이전 분석을 다음 출제의 학습 근거에서 제외합니다. 기존 기록과 판정은 보존합니다. 재게시 기능은 아직 제공하지 않습니다.</p>
    {relatedStructures&&<p>이 구조를 재사용한 내 문제들도 함께 보류하며, 아직 생성 중인 문제는 게시하지 않습니다.</p>}
    <form onSubmit={async event=>{event.preventDefault();if(busy)return;setBusy(true);setError('');try{
      const saved=await api(`/api/problems/${item.problemVersion}/review-hold`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({reason})});
      onHeld({...item,problemHeld:saved.held,reviewReason:saved.reason});
      window.dispatchEvent(new Event('gamjaoj-problems-changed'));
    }catch(e){setError(e.message);}finally{setBusy(false);}}}>
      <label>검토 사유<textarea value={reason} onChange={e=>setReason(e.target.value)} required maxLength={500} rows={2} disabled={busy}/></label>
      <button className="secondary" disabled={busy||!reason.trim()}>{busy?'보류 중…':'이 문제 풀이 보류'}</button>
    </form>{error&&<p className="notice error" role="alert">{error}</p>}
  </details>;
}
