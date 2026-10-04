'use client';
import SelectControl from './select-control';
import {decodeCalls,defaultCall,encodeCalls} from './callable-tests.mjs';

export default function CallableCaseEditor({value,definition,index,onChange,disabled}){
 const rows=value.callRows||(!value.input.trim()?[defaultCall(definition)]:decodeCalls(value.input,value.output||'',definition));
 const raw=value.raw===true||!rows;
 function update(next){const result=encodeCalls(next,definition);onChange({...value,callRows:next,raw:false,...(result.error?{draftError:result.error}:{input:result.input,output:result.output,draftError:null})});}
 async function file(event,key){
  const chosen=event.target.files?.[0];event.target.value='';if(!chosen)return;
  if(chosen.size>16384){onChange({...value,fileError:'파일은 16 KiB 이내로 골라 주세요.'});return;}
  try{const text=await chosen.text();onChange(current=>({...current,[key]:text,callRows:null,raw:true,draftError:null,fileError:null}));}
  catch{onChange({...value,fileError:'파일을 읽지 못했어요. 다시 선택해 주세요.'});}
 }
 return <div className="callable-case-editor">
  <div className="callable-case-toolbar"><strong>추가 {index+1} · 호출 테스트</strong><button type="button" className="secondary" disabled={disabled||!!value.draftError} onClick={()=>{
   if(raw){const parsed=decodeCalls(value.input,value.output||'',definition);if(parsed)update(parsed);else if(!value.input.trim())update([defaultCall(definition)]);else onChange({...value,fileError:'여러 독립 테스트나 잘못된 원문은 호출 편집기로 전환할 수 없어요. 원문에서 편집해 주세요.'});}
   else onChange({...value,raw:true,callRows:null,draftError:null});
  }}>{raw?'호출 편집기로 전환':'입력·출력 원문 편집'}</button></div>
  {raw?<div className="callable-raw-fields"><label>호출 입력 · JSON<textarea aria-label={`추가 ${index+1} · 입력`} rows={4} maxLength={16384} value={value.input} disabled={disabled} spellCheck={false} onChange={e=>onChange({...value,input:e.target.value,fileError:null})}/></label><label>기댓값 · 반환값을 한 줄씩<textarea aria-label={`추가 ${index+1} · 기댓값`} rows={3} maxLength={16384} value={value.output||''} disabled={disabled} spellCheck={false} onChange={e=>onChange({...value,output:e.target.value,fileError:null})}/></label></div>
  :<>
   <p className="callable-case-note">위에서 아래로 호출해요. {definition.mode==='MULTI_API'?'첫 호출은 init이며, 같은 테스트의 호출은 상태를 공유해요.':'solution을 한 번 호출해요.'} 문제에 적힌 인자 범위도 지켜 주세요.</p>
   {rows.map((row,i)=>{
    const m=definition.methods.find(m=>m.name===row.method);
    const change=patch=>update(rows.map((r,j)=>j===i?{...r,...patch}:r));
    return <div key={i} className="callable-call"><div className="callable-call-heading"><label>{i+1}번째 함수<SelectControl aria-label={`추가 ${index+1} · 호출 ${i+1} 함수`} value={row.method} disabled={disabled||definition.mode==='SINGLE_FUNCTION'} onChange={e=>update(rows.map((r,j)=>j===i?defaultCall(definition,e.target.value):r))}>{definition.methods.map(m=><option key={m.name} value={m.name}>{m.name} → {m.returns}</option>)}</SelectControl></label><button type="button" className="secondary" disabled={disabled||rows.length===1} aria-label={`추가 ${index+1} · 호출 ${i+1} 삭제`} onClick={()=>update(rows.filter((_,j)=>j!==i))}>삭제</button></div>
     <details className="callable-method-help"><summary>함수 설명</summary><p>{m.description}</p></details>
     <div className="callable-arguments">{m.parameters.map((p,j)=><label key={p.name}><span>{p.name} <small>{p.type}</small></span><input aria-label={`추가 ${index+1} · 호출 ${i+1} · ${p.name}`} value={row.args[j]} maxLength={16384} disabled={disabled} spellCheck={false} placeholder={p.type.endsWith('[]')?'예: [1, 2, 3]':p.type==='String'?'따옴표 없이 문자열 입력':p.type==='boolean'?'true 또는 false':'정수 입력'} onChange={e=>change({args:row.args.map((a,k)=>k===j?e.target.value:a)})}/></label>)}</div>
     {m.returns==='void'?<p className="callable-case-note">반환값 없음 · 출력에 포함되지 않아요.</p>:<><label className="callable-compare"><input type="checkbox" checked={row.compare} disabled={disabled} onChange={e=>change({compare:e.target.checked})}/>이 반환값을 기댓값과 비교</label>{row.compare&&<label><span>기댓값 <small>{m.returns}</small></span><input aria-label={`추가 ${index+1} · 호출 ${i+1} 기댓값`} value={row.expected} maxLength={16384} disabled={disabled} spellCheck={false} placeholder={m.returns==='String'?'따옴표 없이 입력 · 빈 문자열도 가능':m.returns.endsWith('[]')?'예: [1, 2, 3]':'예상 반환값'} onChange={e=>change({expected:e.target.value})}/></label>}</>}
    </div>;
   })}
   {definition.mode==='MULTI_API'&&<button type="button" className="secondary" disabled={disabled||rows.length>=1000} onClick={()=>update([...rows,defaultCall(definition,definition.methods.find(m=>m.name!=='init')?.name)])}>+ 함수 호출 추가</button>}
   {value.draftError&&<p role="alert" className="notice error">{value.draftError}</p>}
  </>}
  <details className="callable-files"><summary>입력·출력 파일 불러오기</summary><p className="callable-case-note">이 서비스의 호출 JSON 입력과 반환값 출력 파일을 읽어요. 삼성의 숫자 명령 파일은 원본 문제의 구동 코드에 따라 달라 그대로 호환되지 않아요.</p><label>호출 입력 파일<input type="file" accept=".txt,.json,text/plain,application/json" disabled={disabled} aria-label={`추가 ${index+1} 입력 파일`} onChange={e=>file(e,'input')}/></label><label>기댓값 파일<input type="file" accept=".txt,.json,text/plain,application/json" disabled={disabled} aria-label={`추가 ${index+1} 기댓값 파일`} onChange={e=>file(e,'output')}/></label></details>
  {value.fileError&&<p role="alert" className="notice error">{value.fileError}</p>}
 </div>;
}
