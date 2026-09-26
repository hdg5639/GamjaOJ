'use client';
import {useRef,useState} from 'react';
export default function DiagnosticCorrection({api,path,row,index,onSaved}) {
  const [note,setNote]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const pending=useRef(null),lock=useRef(false);
  async function save(event){event.preventDefault();if(lock.current)return;lock.current=true;setBusy(true);setError('');
    pending.current ||= {key:crypto.randomUUID(),body:{observationIndex:index,note}};
    try{
      const value=await api(`${path}/${row.id}/corrections`,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},body:JSON.stringify(pending.current.body)});
      pending.current=null;setNote('');onSaved(value);
    }catch(e){if(e.status>=400&&e.status<500)pending.current=null;setError(e.message);}
    finally{lock.current=false;setBusy(false);}
  }
  return <details className="diagnostic-correction"><summary>이 해석에 의견 남기기</summary>
    <p className="muted">AI 해석과 다르게 생각한 이유를 남겨 주세요. 사용자 설명으로 기록하며 판정이나 원래 해석을 바꾸거나 AI를 다시 호출하지 않습니다.</p>
    {(row.corrections||[]).filter(c=>c.observationIndex===index).map(c=><p className="notice" key={c.id}>내 정정 의견: {c.note}</p>)}
    <form onSubmit={save}><label>관찰 {index+1} 정정 설명<textarea required maxLength={1000} rows={3} value={note} disabled={busy||!!pending.current} onChange={e=>setNote(e.target.value)}/></label>
      <button className="secondary" disabled={busy||!note.trim()}>{busy?'저장 중…':pending.current?'같은 정정 다시 확인':'정정 의견 저장'}</button>
    </form>{error&&<p role="alert" className="notice error">{error}</p>}
  </details>;
}
