'use client';

import {useEffect,useRef,useState} from 'react';
import Pager,{usePage} from './pager';
import RuleOnboarding from './rule-onboarding';

const activeStates=['QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING'];
const handoffs=['VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED'];
const active=job=>activeStates.includes(job.status)||(job.status==='HELD'&&handoffs.includes(job.error));
const states={QUEUED:'출제 대기',DESIGNING:'규칙 준비 중',BUILDING:'문제 작성 중',VALIDATING:'실행 검증 중',REVIEWING:'본문·해설 검토 중',PUBLISHED:'풀이 준비 완료',CANCELLED:'요청 취소됨',DEADLINE_EXCEEDED:'처리 기한 초과',FAILED:'생성 실패',HELD:'검토 보류'};
const stages=[['CONTRACT','규칙 확정'],['CORE','코드 작성'],['PRESENTATION','본문 작성'],['READER','독립 검토'],['VALIDATION','실행 검증'],['CONTENT_REVIEW','최종 검토']];
const branchStates={NOT_STARTED:'대기',QUEUED:'대기',BLOCKED:'대기',RUNNING:'진행 중',EARLY:'먼저 진행 중',SUCCEEDED:'완료',CHECKED:'완료',FAILED:'실패',CANCELLED:'중단'};

export default function HybridGeneration({userId,api,onOpen,onActive,visible,otherActive,ruleDraft}) {
  const [profileId,setProfileId]=useState('zero-one-items-v1');
  const [options,setOptions]=useState(null),[jobs,setJobs]=useState([]),[loaded,setLoaded]=useState(false);
  const [optionsError,setOptionsError]=useState(''),[listError,setListError]=useState(''),[error,setError]=useState('');
  const [shared,setShared]=useState(false),[consent,setConsent]=useState(false),[busy,setBusy]=useState(false),[pending,setPending]=useState(null);
  const live=useRef(false),revision=useRef(0),busyRef=useRef(false),pendingRef=useRef(null),results=useRef(null);
  const storageKey=`gamjaoj-hybrid-request-${userId}`;
  const running=jobs.some(active);
  function savePending(value){pendingRef.current=value;setPending(value);try{if(value)sessionStorage.setItem(storageKey,JSON.stringify(value));else sessionStorage.removeItem(storageKey);}catch{}}
  async function loadOptions(){
    try{const value=await api('/api/generation/hybrid/options');if(live.current){setOptions(value);setOptionsError('');}}
    catch(e){if(live.current)setOptionsError(e.message);}
  }
  async function refresh(){
    const request=++revision.current;
    try{
      const data=await api('/api/generation/hybrid');
      if(!live.current||request!==revision.current)return;
      setJobs(data);setLoaded(true);setListError('');
      if(pendingRef.current&&data.some(job=>job.id===pendingRef.current.key)){savePending(null);setError('');}
    }catch(e){if(live.current&&request===revision.current)setListError(e.message);}
  }
  useEffect(()=>{
    live.current=true;
    try{const saved=JSON.parse(sessionStorage.getItem(storageKey));
      if(saved&&/^[0-9a-f-]{36}$/i.test(saved.key)&&typeof saved.body?.profileId==='string'&&saved.body.profileId.length>0&&typeof saved.body.shared==='boolean'&&saved.body.publishOnSuccess===true){savePending(saved);setProfileId(saved.body.profileId);setShared(saved.body.shared);setConsent(true);}
    }catch{}
    loadOptions();refresh();
    return()=>{live.current=false;revision.current++;};
  },[storageKey]);
  useEffect(()=>{onActive(running||!!pending);},[running,!!pending,onActive]);
  // The catalog is data-backed: fall back to the first selectable rule when the current one is gone.
  useEffect(()=>{
    const list=options?.profiles;if(!list?.length||pendingRef.current)return;
    if(!list.some(item=>item.id===profileId)){setProfileId(list[0].id);setConsent(false);}
  },[options]);
  useEffect(()=>{
    if(!visible&&!running)return;
    const timer=setInterval(()=>{if(!busyRef.current)refresh();},5000);return()=>clearInterval(timer);
  },[visible,running]);
  async function create(event){
    event.preventDefault();if(busyRef.current)return;
    if(!pendingRef.current&&(!options?.enabled||!profile||!consent||running||otherActive||!loaded))return;
    const request=pendingRef.current||{key:crypto.randomUUID(),body:{profileId:profile.id,shared,publishOnSuccess:true}};
    savePending(request);busyRef.current=true;setBusy(true);setError('');revision.current++;
    try{
      const job=await api('/api/generation/hybrid',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':request.key},body:JSON.stringify(request.body)});
      if(!live.current)return;
      revision.current++;savePending(null);setJobs(old=>[job,...old.filter(item=>item.id!==job.id)]);setLoaded(true);
      requestAnimationFrame(()=>results.current?.focus());
    }catch(e){if(live.current){setError(e.message);if(e.status>=400&&e.status<500&&e.status!==401)savePending(null);}}
    finally{busyRef.current=false;if(live.current)setBusy(false);}
  }
  async function action(job,cancel){
    if(busyRef.current)return;
    busyRef.current=true;setBusy(true);setError('');revision.current++;
    try{
      if(cancel){const value=await api(`/api/generation/hybrid/${job.id}/cancel`,{method:'POST'});if(live.current){revision.current++;setJobs(old=>old.map(item=>item.id===value.id?value:item));}}
      else await onOpen(job.publishedVersionId);
    }catch(e){if(live.current)setError(e.message);}
    finally{busyRef.current=false;if(live.current)setBusy(false);}
  }
  const profile=options?.profiles?.find(item=>item.id===profileId);
  const jobPaging=usePage(jobs,5);
  const profileLabel=id=>options?.profiles?.find(item=>item.id===id)?.label||(id==='dijkstra-shortest-path-v1'?'다익스트라 · 가중치 최단 거리':id==='bfs-shortest-path-v1'?'BFS · 무방향 그래프 최단 거리':id&&id!=='zero-one-items-v1'?'규칙 고정 연습 문제':'0/1 배낭 · 물건 선택');
  return <div className="generation-layout hybrid-generation">
    <section className="generation-compose" aria-labelledby="hybrid-heading">
      <div className="generation-section-heading"><h3 id="hybrid-heading">정해진 규칙으로 새 문제 만들기</h3><a href="#hybrid-generation-results">진행·결과로 이동</a></div>
      <p className="draft-help">실험 기능 · 연습할 유형을 선택하고 규칙을 확인해 주세요. 목록에 없는 규칙은 직접 요청하기를 이용해 주세요.</p>
      {optionsError&&<p className="notice error" role="alert">출제 가능 여부를 불러오지 못했어요. {optionsError} <button className="secondary" onClick={loadOptions}>출제 가능 여부 다시 확인</button></p>}
      {!options&&!optionsError&&<p role="status">지원 규칙을 불러오고 있어요…</p>}
      {options?.profiles?.length>0&&<label className="field">문제 유형<select value={profileId} disabled={busy||!!pending||running||otherActive||!options.enabled} onChange={e=>{setProfileId(e.target.value);setConsent(false);}}>{options.profiles.map(item=><option key={item.id} value={item.id}>{item.label}</option>)}</select></label>}
      {profile&&<div className="hybrid-scope"><h4>{profile.label}</h4><p>{profile.description}</p><ul>{profile.rules.map(rule=><li key={rule}>{rule}</li>)}</ul>{profile.verifiedReference&&<p className="draft-help">이 규칙은 검증을 통과한 정답 코드를 다시 사용해 코드 작성 단계를 생략합니다. 본문·힌트·해설과 실행 검증·최종 검토는 새로 진행합니다.</p>}</div>}
      {options&&!options.enabled&&<p className="notice">{options.message}</p>}
      <form onSubmit={create}>
        <fieldset className="hybrid-consent" disabled={busy||!!pending||!options?.enabled}>
          <legend>완료 후 게시 범위</legend>
          <label className="check-row"><input type="checkbox" checked={shared} onChange={e=>setShared(e.target.checked)}/>다른 회원에게도 공개</label>
          <p className="draft-help">기본은 나만 보기입니다. 공개하면 본문·예제·힌트·해설을 다른 회원도 볼 수 있어요.</p>
          <label className="check-row"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/>위 규칙으로 생성하고 검증 통과 시 게시</label>
        </fieldset>
        <p className="draft-help">요청 시 서비스 AI 예산을 사용합니다. 최대 10분의 처리 기한 안에 검증을 마치지 못하면 게시하지 않으며, 자동 재요청은 하지 않습니다.</p>
        {pending&&<p className="notice" role="status">요청의 접수 여부를 확인하고 있어요. 다시 누르면 같은 요청을 확인합니다.</p>}
        {(running||otherActive)&&!pending&&<p className="notice" role="status">출제가 진행 중이에요. 현재 요청이 끝나면 새로 만들 수 있습니다.</p>}
        <button className="primary" disabled={busy||(!pending&&(!options?.enabled||!profile||!consent||running||otherActive||!loaded))}>{busy?'처리 중…':pending?'기존 요청 확인':'이 규칙으로 생성·게시'}</button>
      </form>
      {error&&<p className="notice error" role="alert">{error}</p>}
      <RuleOnboarding api={api} onRegistered={loadOptions} draft={ruleDraft} onOpen={onOpen}/>
    </section>
    <section className="generation-results" aria-labelledby="hybrid-generation-results">
      <h3 id="hybrid-generation-results" tabIndex={-1} ref={results}>진행·결과</h3>
      <p className="draft-help">최근 30건 · 화면을 벗어나도 접수된 요청은 계속됩니다. 다음에 돌아와 이어서 확인할 수 있어요.</p>
      {listError&&<p className="notice error" role="alert">진행 상태를 새로 확인하지 못했어요. {listError} <button className="secondary" onClick={refresh} disabled={busy}>상태 다시 확인</button></p>}
      {!loaded&&!listError&&<p role="status">출제 기록을 불러오고 있어요…</p>}
      {loaded&&!jobs.length&&<p>아직 요청한 문제가 없어요.</p>}
      <div aria-live="polite">{jobPaging.visible.map((job,index)=><details key={job.id} className="generation-job" open={(jobPaging.offset+index)===0||active(job)}>
        <summary><strong>{profileLabel(job.profileId)}</strong><span className="generation-status">{job.problemHeld?'게시 후 검토 보류':job.status==='HELD'&&handoffs.includes(job.error)?'다음 단계 준비 중':states[job.status]||'진행 상태 확인 필요'}</span></summary>
        <div className="generation-job-body">
          <p className="draft-help">{new Date(job.acceptedAt).toLocaleString('ko-KR')} · {job.shared?'다른 회원에게 공개':'나만 보기'}</p>
          <ol className="hybrid-stages" aria-label="출제 단계">{stages.map(([key,label])=><li key={key}><span>{label}</span><strong>{key==='CORE'&&job.referenceReused&&job.branches?.[key]==='SUCCEEDED'?'검증된 코드 재사용':branchStates[job.branches?.[key]]||'대기'}</strong></li>)}</ol>
          {active(job)&&<><p className="draft-help">처리 기한: {new Date(job.deadlineAt).toLocaleTimeString('ko-KR')}. 취소하면 이후 작업과 게시를 중단합니다. 이미 시작한 호출은 비용이 발생할 수 있어요.</p><button className="secondary" disabled={busy} onClick={()=>action(job,true)}>이 요청 취소</button></>}
          {job.status==='PUBLISHED'&&<button className="primary" disabled={busy||job.problemHeld||!job.publishedVersionId} onClick={()=>action(job,false)}>이 문제 풀기</button>}
          {job.status==='DEADLINE_EXCEEDED'&&<p>처리 기한 내에 마치지 못해 게시하지 않았어요. 이전 요청은 자동으로 다시 실행되지 않습니다.</p>}
          {job.status==='HELD'&&!active(job)&&<p>{job.error==='CODEX_QUOTA_API_BUDGET'?'출제 모델 사용 한도에 도달했고, 대체 실행에 필요한 AI 예산도 부족해 중단했어요.':'게시 조건을 충족하지 못해 중단했어요.'} 진행 정보는 보존되며 자동으로 다시 생성하지 않습니다.</p>}
          {job.status==='FAILED'&&<p>생성을 완료하지 못해 게시하지 않았어요.</p>}
          {job.problemHeld&&<p>이 문제는 게시 후 검토 중이에요. 검토가 끝날 때까지 새 풀이를 시작할 수 없습니다.</p>}
        </div>
      </details>)}</div>
      <Pager paging={jobPaging} label="규칙 고정 출제 결과 페이지"/>
    </section>
  </div>;
}
