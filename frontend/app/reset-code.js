'use client';
import {useState} from 'react';
/** Two-step reset to the language template; undo (Ctrl/Cmd+Z) in the editor restores the previous code. */
export default function ResetCode({disabled,onReset}) {
  const [asking,setAsking]=useState(false);
  if(!asking)return <button type="button" className="secondary reset-code" disabled={disabled} onClick={()=>setAsking(true)}>코드 초기화</button>;
  return <span className="reset-code-confirm" role="group" aria-label="코드 초기화 확인">
    <span>작성한 코드를 기본 템플릿으로 바꿀까요?</span>
    <button type="button" className="danger" disabled={disabled} onClick={()=>{onReset();setAsking(false);}}>초기화</button>
    <button type="button" className="secondary" onClick={()=>setAsking(false)}>취소</button>
  </span>;
}
