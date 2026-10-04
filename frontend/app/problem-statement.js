'use client';
import {useEffect,useMemo,useRef,useState} from 'react';
import Markdown,{defaultUrlTransform} from 'react-markdown';
import remarkGfm from 'remark-gfm';
import Modal from './modal';

import {statementImageUrl,statementIllustrationLayout,remarkStatementIllustrations} from './statement-illustrations.mjs';
export {statementImageUrl} from './statement-illustrations.mjs';
function StatementImage({src,alt='',caption='',width,height,onExpand}){
 const [failed,setFailed]=useState(false);useEffect(()=>setFailed(false),[src]);
 return <span className="statement-image">{failed?<span className="statement-image-missing" role="status">그림을 불러오지 못했어요. {alt}<button type="button" className="secondary" onClick={()=>setFailed(false)}>다시 불러오기</button></span>:<button type="button" className="statement-image-button" aria-label={`${alt||'문제 그림'} 확대`} onClick={()=>onExpand({src,alt,caption})}><img src={src} alt={alt} width={width} height={height} loading="lazy" decoding="async" onError={()=>setFailed(true)}/><span className="statement-image-hint">클릭하여 확대</span></button>}{caption&&<span className="statement-image-caption">{caption}</span>}</span>;
}
export default function ProblemStatement({statement='',version,api,className='',manage=true}){
 const [presentation,setPresentation]=useState({canEdit:false,illustrations:[]}),[expanded,setExpanded]=useState(null),[editing,setEditing]=useState(false),[file,setFile]=useState(null),[preview,setPreview]=useState(''),[alt,setAlt]=useState(''),[caption,setCaption]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[loadError,setLoadError]=useState(''),[pending,setPending]=useState(null),[deleting,setDeleting]=useState(null);
 const revision=useRef(0),executionLock=useRef(false),fileInput=useRef(null);
 const path=version?`/api/problems/${encodeURIComponent(version)}/illustrations`:null;
 async function refresh(){if(!api||!path)return;const r=++revision.current;try{const value=await api(path);if(r===revision.current){setPresentation({canEdit:value?.canEdit===true,illustrations:Array.isArray(value?.illustrations)?value.illustrations:[]});setLoadError('');}}catch(e){if(r===revision.current&&e.status!==404)setLoadError('문제 그림을 불러오지 못했어요.');}}
 useEffect(()=>{revision.current++;setPresentation({canEdit:false,illustrations:[]});setExpanded(null);setEditing(false);setFile(null);setPending(null);setError('');setLoadError('');if(api&&path)refresh();return()=>{revision.current++;};},[path,api]);
 useEffect(()=>{if(!file){setPreview('');return;}const url=URL.createObjectURL(file);setPreview(url);return()=>URL.revokeObjectURL(url);},[file]);
 function chooseFile(next){if(!next||busy||pending)return;if(!['image/png','image/jpeg'].includes(next.type)||next.size>3*1024*1024){setError('PNG·JPEG 그림을 3 MiB 이내로 선택해 주세요.');return;}setFile(next);setError('');}
 function paste(event){const item=Array.from(event.clipboardData?.items||[]).find(i=>i.kind==='file'&&i.type.startsWith('image/'));if(item){event.preventDefault();chooseFile(item.getAsFile());}}
 async function upload(attempt){if(executionLock.current)return;executionLock.current=true;setBusy(true);setError('');setPending(attempt);const form=new FormData();form.append('file',attempt.file);form.append('alt',attempt.alt);form.append('caption',attempt.caption);
  try{const value=await api(path,{method:'POST',headers:{'Idempotency-Key':attempt.key},body:form});revision.current++;setPresentation(value);setPending(null);setFile(null);setAlt('');setCaption('');}
  catch(e){setError(e.message);if(e.status>=400&&e.status<500)setPending(null);else{try{const value=await api(path);if(value.illustrations?.some(i=>i.id===attempt.key)){revision.current++;setPresentation(value);setPending(null);setFile(null);setAlt('');setCaption('');setError('');}}catch{}}}
  finally{executionLock.current=false;setBusy(false);}
 }
 async function remove(image){if(executionLock.current)return;executionLock.current=true;setBusy(true);setError('');try{const value=await api(`${path}/${image.id}`,{method:'DELETE'});revision.current++;setPresentation(value);setDeleting(null);}catch(e){setError(e.message);}finally{executionLock.current=false;setBusy(false);}}
 const layout=useMemo(()=>statementIllustrationLayout(statement,presentation.illustrations),[statement,presentation.illustrations]);
 const components=useMemo(()=>({
  img:({src,alt,title})=>statementImageUrl(src)?<StatementImage src={src} alt={alt} caption={title} width={presentation.illustrations.find(i=>i.src===src)?.width} height={presentation.illustrations.find(i=>i.src===src)?.height} onExpand={setExpanded}/>:<span className="statement-image-missing">그림: {alt||'설명 이미지'} · 등록된 이미지 주소가 필요해요.</span>,
  a:({href,children})=><a href={href} target="_blank" rel="noopener noreferrer">{children}</a>,
  table:({children})=><div className="statement-table-scroll"><table>{children}</table></div>,
 }),[presentation.illustrations]);
 return <div className={`problem-statement ${className}`}>
  <Markdown remarkPlugins={[remarkGfm,[remarkStatementIllustrations,{placements:layout.placements}]]} skipHtml components={components} urlTransform={(url,key)=>key==='src'?statementImageUrl(url):defaultUrlTransform(url)}>{typeof statement==='string'?statement:''}</Markdown>
  {loadError&&<p className="statement-illustration-error" role="status">{loadError} <button type="button" className="secondary" onClick={refresh}>다시 불러오기</button></p>}
  {layout.remainder.length>0&&<section className="statement-illustrations" aria-label="문제 이해 그림">{layout.remainder.map((image,i)=>statementImageUrl(image.src)&&<figure key={image.id||image.src||i}><StatementImage {...image} onExpand={setExpanded}/></figure>)}</section>}
  {manage&&presentation.canEdit&&<button type="button" className="secondary statement-manage" onClick={()=>setEditing(true)}>문제 그림 관리</button>}
  <Modal open={!!expanded} title={expanded?.alt||'문제 그림 확대'} onClose={()=>setExpanded(null)} wide className="statement-image-dialog">{expanded&&<><img className="statement-expanded-image" src={expanded.src} alt={expanded.alt}/>{expanded.caption&&<p>{expanded.caption}</p>}</>}</Modal>
  <Modal open={editing} title="문제 그림 관리" onClose={()=>{if(!busy)setEditing(false);}} className="diagnostic-dialog" wide><div className="diagnostic-dialog-content statement-image-manager">
   <p className="muted">격자·연결 관계·이동 규칙처럼 문제 이해에 필요한 그림을 추가해요. 예제 처리 순서 그림은 명령 처리 문제에 사용해 주세요.</p>
   <div className="statement-upload-zone" onPaste={paste} tabIndex={0} aria-label="그림 붙여넣기 영역"><button type="button" className="secondary" disabled={busy||!!pending} onClick={()=>fileInput.current?.click()}>그림 파일 선택</button><span>이 영역에서 이미지 붙여넣기도 가능해요.</span><input ref={fileInput} type="file" accept="image/png,image/jpeg" hidden onChange={e=>{chooseFile(e.target.files?.[0]);e.target.value='';}}/>{preview&&<img src={preview} alt="추가할 그림 미리보기"/>}</div>
   <p className="draft-help">PNG·JPEG 3 MiB 이하 · 한 문제 최대 8개 · 원문과 채점 데이터는 유지돼요.</p>
   <label>그림 설명<input value={alt} maxLength={240} disabled={busy||!!pending} onChange={e=>setAlt(e.target.value)} placeholder="예: 네 방향으로 이동할 수 있는 격자와 벽"/></label>
   <label>캡션<textarea rows={2} value={caption} maxLength={600} disabled={busy||!!pending} onChange={e=>setCaption(e.target.value)} placeholder="그림의 기호와 규칙을 간단히 설명해 주세요."/></label>
   <button type="button" className="primary" disabled={busy||!!pending||!file||!alt.trim()} onClick={()=>upload({key:crypto.randomUUID(),file,alt:alt.trim(),caption:caption.trim()})}>그림 추가</button>
   {pending&&<button type="button" className="secondary" disabled={busy} onClick={()=>upload(pending)}>같은 업로드 요청 다시 확인</button>}
   {error&&<p className="notice error" role="alert">{error}</p>}
   <ul className="statement-upload-list">{presentation.illustrations.map((image,i)=><li key={image.id||image.src||i}><span>{image.alt}</span>{image.id&&<button type="button" className="secondary" disabled={busy||!!pending} onClick={()=>setDeleting(image)}>삭제</button>}</li>)}</ul>
   {deleting&&<div className="notice"><p>‘{deleting.alt}’ 그림을 삭제할까요?</p><div className="training-tool-actions"><button type="button" className="secondary" disabled={busy} onClick={()=>setDeleting(null)}>취소</button><button type="button" className="secondary" disabled={busy} onClick={()=>remove(deleting)}>이 그림 삭제</button></div></div>}
  </div></Modal>
 </div>;
}
