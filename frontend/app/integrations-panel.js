'use client';
import {useEffect,useState} from 'react';
const names={GITHUB:'GitHub',NOTION:'Notion'};
const statuses={QUEUED:'저장 대기',RUNNING:'저장 중',RETRY:'자동 재시도 대기',SUCCEEDED:'저장 완료',FAILED:'저장 실패',CANCELLED:'취소됨'};
const errors={RECONNECT_REQUIRED:'계정을 다시 연결해 주세요.',PERMISSION_REQUIRED:'저장 위치의 쓰기 권한을 확인해 주세요.',TARGET_NOT_FOUND:'저장 위치를 찾을 수 없어요.',TARGET_CHANGED:'저장 위치가 변경됐어요.',DELIVERY_UNCERTAIN:'저장 여부를 확인하지 못했어요. Notion 페이지를 확인한 뒤 재시도해 주세요.',MANAGED_CONTENT_MISSING:'자동 관리하는 코드 블록을 찾을 수 없어요.',RATE_LIMITED:'요청이 많아 잠시 기다리고 있어요.',SERVICE_UNAVAILABLE:'연동 서비스를 준비 중이에요.'};
const json=(method,body)=>({method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});

function Destination({connection:c,api,onChange}){
 const [targets,setTargets]=useState([]),[search,setSearch]=useState(''),[selected,setSelected]=useState(c.target?.repo||c.target?.id||'');
 const [branch,setBranch]=useState(c.target?.branch||''),[prefix,setPrefix]=useState(c.target?.prefix||'GamjaOJ'),[automatic,setAutomatic]=useState(c.autoEnabled);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[loaded,setLoaded]=useState(false),[confirm,setConfirm]=useState(false);
 async function action(fn){setBusy(true);setError('');setMessage('');try{await fn();}catch(e){setError(e.message);}finally{setBusy(false);}}
 const ready=c.available&&c.status==='CONNECTED';
 async function find(){await action(async()=>{setTargets(await api(`/api/integrations/${c.provider}/targets?search=${encodeURIComponent(search)}`));setLoaded(true);});}
 async function save(e){e.preventDefault();await action(async()=>{await api(`/api/integrations/${c.provider}/target`,json('PUT',{targetId:selected,branch,prefix,autoEnabled:automatic}));setMessage('저장 위치와 자동 저장 설정을 적용했어요.');await onChange();});}
 return <section className="export-provider" aria-label={`${names[c.provider]} 연동`}>
  <div className="export-heading"><h3>{names[c.provider]}</h3><span className="muted">{c.connected?c.account:'연결 안 됨'}</span></div>
  {!c.available&&<p className="muted">서비스 연동을 준비 중이에요. 준비가 끝나면 여기서 연결할 수 있어요.</p>}
  {c.available&&(!c.connected||c.status!=='CONNECTED')&&<><p className="muted">{c.status==='RECONNECT_REQUIRED'?'연결이 만료됐어요. 다시 연결해 주세요.':c.provider==='GITHUB'?'앱을 설치한 저장소 중에서 풀이를 저장할 곳을 선택하세요.':'공유를 허용한 페이지 아래에 풀이를 저장해요.'}</p>
   {c.provider==='GITHUB'&&c.installUrl&&<p><a href={c.installUrl} target="_blank" rel="noopener noreferrer">GitHub App 설치 / 저장소 권한 설정 ↗</a></p>}
   <button className="secondary" disabled={busy} onClick={()=>action(async()=>{const result=await api(`/api/integrations/${c.provider}/connect`,{method:'POST'});window.location.assign(result.url);})}>{busy?'연결 중…':`${names[c.provider]} 연결`}</button></>}
  {ready&&c.provider==='GITHUB'&&c.installUrl&&<p><a href={c.installUrl} target="_blank" rel="noopener noreferrer">저장소 접근 권한 변경 ↗</a></p>}
  {ready&&<form onSubmit={save}>
   {c.target&&<p className="export-current">저장 위치: <a href={c.target.url} target="_blank" rel="noopener noreferrer">{c.target.label}</a>{c.provider==='GITHUB'&&` · ${c.target.private?'비공개':'공개'} 저장소`}</p>}
   <div className="export-search"><label>{c.provider==='GITHUB'?'저장소 검색':'페이지 검색'}<input value={search} onChange={e=>setSearch(e.target.value)} maxLength={100} placeholder="이름으로 검색"/></label><button type="button" className="secondary" disabled={busy} onClick={find}>{busy?'확인 중…':'목록 불러오기'}</button></div>
   {loaded&&!targets.length&&<p className="muted">접근 가능한 위치가 없어요. 앱에 공유한 저장소나 페이지를 확인해 주세요.</p>}
   <label>저장 위치<select value={selected} onChange={e=>{setSelected(e.target.value);setBranch(targets.find(t=>t.id===e.target.value)?.branch||'');}} required disabled={busy}>
    <option value="">위치를 선택하세요</option>{c.target&&!targets.some(t=>t.id===(c.target.repo||c.target.id))&&<option value={c.target.repo||c.target.id}>{c.target.label}</option>}
    {targets.map(t=><option key={t.id} value={t.id}>{t.label}{c.provider==='GITHUB'?` (${t.privateTarget?'비공개':'공개'})`:''}</option>)}
   </select></label>
   {c.provider==='GITHUB'&&<div className="export-fields"><label>브랜치<input value={branch} onChange={e=>setBranch(e.target.value)} maxLength={200} placeholder="저장소의 기본 브랜치"/></label><label>저장 폴더<input value={prefix} onChange={e=>setPrefix(e.target.value)} maxLength={120} pattern="[A-Za-z0-9_/-]+" required/></label></div>}
   {c.provider==='GITHUB'&&targets.find(t=>t.id===selected)?.privateTarget===false&&<p className="muted">공개 저장소에 올린 코드는 누구나 볼 수 있어요.</p>}
   <label className="export-toggle"><input type="checkbox" checked={automatic} onChange={e=>setAutomatic(e.target.checked)}/>앞으로 통과한 풀이 자동 저장</label>
   <button className="primary" disabled={busy||!selected}>{busy?'처리 중…':'저장 설정 적용'}</button>
  </form>}
  {c.connected&&<div className="export-disconnect">{confirm?<><p>이후 저장을 중단해요. 이미 외부에 저장한 파일과 페이지는 남아요.</p><button className="danger" disabled={busy} onClick={()=>action(async()=>{await api(`/api/integrations/${c.provider}`,{method:'DELETE'});await onChange();})}>연결 해제 확인</button><button className="secondary" disabled={busy} onClick={()=>setConfirm(false)}>취소</button></>:<button className="secondary" disabled={busy} onClick={()=>setConfirm(true)}>연결 해제</button>}</div>}
  {error&&<p role="alert" className="notice error">{error}</p>}{message&&<p role="status" className="notice success">{message}</p>}
 </section>;
}
export function ExportDeliveries({items,onRetry,busy}){return <ul className="export-deliveries">{items.map(item=><li key={item.id}>
 <div><strong>{names[item.provider]} · {item.problemVersion} · {item.language}</strong><span>{statuses[item.status]||item.status}{item.error&&` · ${errors[item.error]||'외부 연결과 권한을 확인한 뒤 다시 시도해 주세요.'}`}</span></div>
 {item.status==='SUCCEEDED'&&item.url&&<a href={item.url} target="_blank" rel="noopener noreferrer">풀이 열기 ↗</a>}
 {['FAILED','RETRY'].includes(item.status)&&<button type="button" className="secondary" disabled={busy} onClick={()=>onRetry(item.id)}>재시도</button>}
 </li>)}</ul>;}
export default function IntegrationsPanel({api}){
 const [connections,setConnections]=useState(null),[deliveries,setDeliveries]=useState([]),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 async function refresh(){const [c,d]=await Promise.all([api('/api/integrations'),api('/api/integrations/deliveries')]);setConnections(c);setDeliveries(d);}
 useEffect(()=>{let live=true;Promise.all([api('/api/integrations'),api('/api/integrations/deliveries')]).then(([c,d])=>{if(live){setConnections(c);setDeliveries(d);}}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};},[api]);
 useEffect(()=>{if(!deliveries.some(d=>['QUEUED','RUNNING','RETRY'].includes(d.status)))return;let live=true;const timer=setTimeout(()=>api('/api/integrations/deliveries').then(d=>{if(live)setDeliveries(d);}).catch(e=>{if(live)setError(e.message);}),5000);return()=>{live=false;clearTimeout(timer);};},[deliveries,api]);
 return <section className="integrations" aria-labelledby="integrations-heading"><h2 id="integrations-heading">풀이 자동 저장</h2>
  <p className="muted">정식 제출에서 통과한 내 코드와 문제 링크를 저장해요. 같은 문제·언어는 최신 통과 코드로 갱신하고, Notion에 직접 쓴 회고는 유지해요. 진단평가와 코드 실행은 제외해요.</p>
  {!connections&&!error&&<p role="status">연결 상태를 불러오고 있어요…</p>}
  {connections?.map(c=><Destination key={c.provider+':'+c.status+':'+c.connected} connection={c} api={api} onChange={refresh}/>)}
  <div className="export-heading"><h3>최근 저장 내역</h3><button className="secondary" disabled={busy} onClick={async()=>{setBusy(true);setError('');try{await refresh();}catch(e){setError(e.message);}finally{setBusy(false);}}}>새로고침</button></div>
  {connections&&!deliveries.length&&<p className="muted">아직 저장 내역이 없어요. 이전 풀이도 문제의 제출 기록에서 저장할 수 있어요.</p>}
  <ExportDeliveries items={deliveries} busy={busy} onRetry={async id=>{setBusy(true);setError('');try{await api(`/api/integrations/deliveries/${id}/retry`,{method:'POST'});await refresh();}catch(e){setError(e.message);}finally{setBusy(false);}}}/>
  {error&&<p role="alert" className="notice error">{error}</p>}
 </section>;
}

export function ExportSubmission({api,submission}){
 const [opened,setOpened]=useState(false),[connections,setConnections]=useState([]),[items,setItems]=useState([]),[busy,setBusy]=useState(false),[error,setError]=useState('');
 useEffect(()=>{if(!opened)return;let live=true,timer;async function load(){try{const [c,d]=await Promise.all([api('/api/integrations'),api(`/api/integrations/deliveries?submissionId=${submission.id}`)]);if(live){setConnections(c);setItems(d);if(d.some(x=>['QUEUED','RUNNING','RETRY'].includes(x.status)))timer=setTimeout(load,5000);}}catch(e){if(live)setError(e.message);}}load();return()=>{live=false;clearTimeout(timer);};},[opened,submission.id,api,busy]);
 async function action(fn){setBusy(true);setError('');try{await fn();}catch(e){setError(e.message);}finally{setBusy(false);}}
 if(submission.verdict!=='AC'||submission.diagnosticItemId)return null;
 return <details className="submission-export" onToggle={e=>setOpened(e.currentTarget.open)}><summary>GitHub · Notion에 풀이 저장</summary>
  <p className="muted">이 제출의 코드를 저장해요. 더 최신의 통과 기록이 저장돼 있으면 그 기록을 유지해요.</p>
  <div className="export-actions">{connections.filter(c=>c.available&&c.connected&&c.status==='CONNECTED'&&c.target).map(c=><button key={c.provider} className="secondary" disabled={busy} onClick={()=>action(()=>api('/api/integrations/exports',json('POST',{provider:c.provider,submissionId:submission.id})))}>{names[c.provider]}에 저장</button>)}<a href="/?settings=integrations">연결 설정</a></div>
  <ExportDeliveries items={items} busy={busy} onRetry={id=>action(()=>api(`/api/integrations/deliveries/${id}/retry`,{method:'POST'}))}/>
  {error&&<p className="notice error" role="alert">{error}</p>}
 </details>;
}
