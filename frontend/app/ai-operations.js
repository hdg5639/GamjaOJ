'use client';
import HybridGeneration from './hybrid-generation';
import SpecDrafts from './spec-drafts';
import ProblemReview from './problem-review';
import { useEffect, useRef, useState } from 'react';
import Pager,{usePage} from './pager';
const states={THEME_FAILED:'소재 준비 실패',QUEUED:'생성 대기',GENERATING:'문제 작성 중',AWAITING_REVIEW:'검증 시작 대기',VALIDATING:'테스트 검증 중',READY:'풀이 준비 완료',FAILED:'검증 실패',NEEDS_AUTH:'생성 서비스 연결 확인 필요',NEEDS_REVIEW:'생성 중단 · 서비스 확인 필요'};
const themeStates={QUEUED:'새 소재 준비 대기',RUNNING:'새 소재 구상 중',HELD_DISABLED:'테마 API 연결 대기',HELD_BUDGET:'테마 API 예산 대기',FAILED:'테마 생성 실패',UNKNOWN:'테마 호출 결과 확인 필요'};
const activeStates=['QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING'];
export default function AiOperations({api,onOpen,userId,initialMode='tags',ruleDraft,visible=true}) {
  const [draftActive,setDraftActive]=useState(false);
  const [hybridActive,setHybridActive]=useState(false),[hybridOpened,setHybridOpened]=useState(initialMode==='hybrid');
  const [shared,setShared]=useState(true);
  const [mode,setMode]=useState(initialMode);
  useEffect(()=>{setMode(initialMode);if(initialMode==='hybrid')setHybridOpened(true);},[initialMode,ruleDraft?.key]);
  const [jobs,setJobs]=useState([]),[error,setError]=useState(''),[busy,setBusy]=useState(false),[loaded,setLoaded]=useState(false);
  const [category,setCategory]=useState('sequences'),[tags,setTags]=useState(['basics']);
  const [options,setOptions]=useState([]),[optionsError,setOptionsError]=useState(''),[optionsAttempt,setOptionsAttempt]=useState(0);
  const choice=options.find(item=>item.id===category);
  const [selection,setSelection]=useState(null),[selectionError,setSelectionError]=useState(''),[selectionAttempt,setSelectionAttempt]=useState(0);
  const selectionKey=category+'|'+[...tags].sort().join(',');
  const resolved=selection?.key===selectionKey?selection.value:null,template=resolved?.template;
  useEffect(()=>{let stopped=false;setSelectionError('');
    if(!choice||!tags.length)return;
    api('/api/generation/selection?'+new URLSearchParams({category,tags:[...tags].sort().join(',')})).then(value=>{
      if(!stopped)setSelection({key:selectionKey,value});
    }).catch(e=>{if(!stopped)setSelectionError(e.message);});
    return()=>{stopped=true;};
  },[selectionKey,choice,selectionAttempt]);
  useEffect(()=>{let stopped=false;setOptionsError('');
    api('/api/generation/options').then(data=>{if(!stopped)setOptions(data);}).catch(e=>{if(!stopped)setOptionsError(e.message);});
    return()=>{stopped=true;};
  },[optionsAttempt]);
  const [learning,setLearning]=useState([]),[sourceAnalysisId,setSourceAnalysisId]=useState(''),[learningError,setLearningError]=useState('');
  useEffect(()=>{let stopped=false;
    setLearning([]);setLearningError('');setSourceAnalysisId('');
    if(!template)return;
    api('/api/generation/learning-context?template='+encodeURIComponent(template)).then(data=>{if(!stopped)setLearning(data);}).catch(e=>{if(!stopped)setLearningError(e.message);});
    return()=>{stopped=true;};
  },[template]);
  const [recommendation,setRecommendation]=useState(null),[recommendationError,setRecommendationError]=useState(''),[recommending,setRecommending]=useState(false);
  const recommendationRequest=useRef(0);
  useEffect(()=>{recommendationRequest.current++;setRecommendation(null);setRecommendationError('');setRecommending(false);return()=>{recommendationRequest.current++;};},[selectionKey]);
  async function recommend(){
    const request=++recommendationRequest.current;
    setRecommending(true);setRecommendationError('');
    try {
      const value=await api('/api/generation/recommendations?'+new URLSearchParams({category,tags:[...tags].sort().join(',')}));
      if(request===recommendationRequest.current)setRecommendation({key:selectionKey,value});
    }catch(e){if(request===recommendationRequest.current)setRecommendationError(e.message);}
    finally{if(request===recommendationRequest.current)setRecommending(false);}
  }
  const suggestions=recommendation?.key===selectionKey?recommendation.value:null;
  const pending=useRef(null);
  const active=draftActive||hybridActive||jobs.some(job=>activeStates.includes(job.status));
  async function refresh(){setJobs(await api('/api/generation'));setLoaded(true);}
  useEffect(()=>{if(!visible)return;let stopped=false;
    async function load(){try{const data=await api('/api/generation');if(!stopped){setJobs(data);setLoaded(true);}}catch(e){if(!stopped)setError(e.message);}}
    load();const timer=setInterval(load,5000);return()=>{stopped=true;clearInterval(timer);};},[visible]);
  async function create(){
    if(busy||!resolved||!tags.length)return;
    setBusy(true);setError('');
    pending.current ||= {key:crypto.randomUUID(),category,tags:[...tags].sort(),sourceAnalysisId,shared};
    try{
      await api('/api/generation',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},
        body:JSON.stringify({category:pending.current.category,tags:pending.current.tags,shared:pending.current.shared,...(pending.current.sourceAnalysisId?{sourceAnalysisId:pending.current.sourceAnalysisId}:{})})});
      pending.current=null;await refresh();requestAnimationFrame(()=>document.getElementById('tag-generation-results')?.focus());
    }catch(e){setError(e.message);if(e.status&&e.status<500)pending.current=null;}finally{setBusy(false);}
  }
  async function open(job){setBusy(true);setError('');try{await onOpen(job.problemVersion);}catch(e){setError(e.message);}finally{setBusy(false);}}
  async function retryTheme(job){setBusy(true);setError('');try{
    await api('/api/generation/'+job.id+'/retry-theme',{method:'POST'});await refresh();
  }catch(e){setError(e.message);}finally{setBusy(false);}}
  async function resume(job){setBusy(true);setError('');try{
    await api('/api/generation/'+job.id+'/review',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({artifactHash:job.artifactHash,approve:true})});await refresh();
  }catch(e){setError(e.message);}finally{setBusy(false);}}
  const jobPaging=usePage(jobs,5);
  return <section className="ai-operations" aria-label="내 문제 생성">
    <header className="generation-header"><h2>다음 연습을 직접 설계해 보세요.</h2>
      <p className="muted">연습할 내용을 고르고, 검증을 마친 나만의 문제를 풀어보세요.</p></header>
    <div className="generation-modes" role="group" aria-label="문제 생성 방식">
      <button aria-pressed={mode==='tags'} aria-controls="tag-generation" onClick={()=>setMode('tags')}>태그로 만들기{jobs.some(job=>activeStates.includes(job.status))?' · 진행 중':''}</button>
      <button aria-pressed={mode==='request'} aria-controls="request-generation" onClick={()=>setMode('request')}>직접 요청하기{draftActive?' · 진행 중':''}</button>
      <button aria-pressed={mode==='hybrid'} aria-controls="hybrid-generation" onClick={()=>{setHybridOpened(true);setMode('hybrid');}}>규칙 고정 출제 · 실험{hybridActive?' · 진행 중':''}</button>
    </div>
    <div id="hybrid-generation" hidden={mode!=='hybrid'}>{hybridOpened&&<HybridGeneration userId={userId} api={api} onOpen={onOpen} ruleDraft={ruleDraft} onActive={setHybridActive} visible={visible&&mode==='hybrid'} otherActive={draftActive||jobs.some(job=>activeStates.includes(job.status))}/>}</div>
    <div id="request-generation" hidden={mode!=='request'}><SpecDrafts visible={visible} api={api} generationActive={hybridActive||jobs.some(job=>activeStates.includes(job.status))} onActive={setDraftActive} onOpen={onOpen}/></div>
    <div id="tag-generation" hidden={mode!=='tags'}>
    <div className="generation-layout">
    <section className="generation-compose" aria-label="태그로 문제 요청">
    <div className="generation-section-heading"><h3>어떤 연습을 할까요?</h3><a href="#tag-generation-results">진행·결과로 이동</a></div>
    <p className="draft-help">카테고리와 연습 태그를 선택하세요. 새 소재로 문제와 힌트를 작성합니다.</p>
    {optionsError?<p role="alert" className="notice error">카테고리를 불러오지 못했어요: {optionsError} <button className="secondary" onClick={()=>setOptionsAttempt(n=>n+1)}>카테고리 다시 불러오기</button></p>:!choice&&<p role="status">카테고리와 태그를 불러오고 있어요…</p>}
    <label>카테고리<select aria-label="카테고리" value={category} disabled={busy||!!pending.current||!choice} onChange={e=>{setCategory(e.target.value);setTags(['basics']);setSourceAnalysisId('');}}>
      {!choice&&<option value={category}>불러오는 중…</option>}
      {options.map(item=><option key={item.id} value={item.id}>{item.label}</option>)}
    </select></label>
    <p className="draft-help">{category==='graphs'?'기본은 무방향·비용 1 그래프의 S→T 최단 거리입니다. 방향·가중치를 추가하거나 도달 정점 수·최대 최단 거리 중 하나를 고를 수 있어요.':category==='sequences'?'수열은 선택 조건 → 값 변환 → 집계를 조합합니다. 조건·변환을 선택하지 않으면 전체 값·원래 값을 사용하며, 기본 집계는 합입니다.':'괄호의 순서와 균형을 검사하는 문제를 만듭니다.'} Java · C++ · Python으로 풀 수 있어요.</p>
    <fieldset className="generation-tags" disabled={busy||!!pending.current||!choice} aria-describedby="generation-tags-help">
      <legend>연습 태그</legend>
      {[...new Set(choice?.tags.map(tag=>tag.group||'연습 목표'))].map(group=><div className="generation-tag-group" key={group}>
        <span>{group}</span><div>{choice.tags.filter(tag=>(tag.group||'연습 목표')===group).map(tag=><label key={tag.id}><input type="checkbox" checked={tags.includes(tag.id)} onChange={e=>setTags(current=>e.target.checked?[...current,tag.id]:current.filter(id=>id!==tag.id))}/>{tag.label}</label>)}</div>
      </div>)}
      <p id="generation-tags-help" className="draft-help">여러 개를 고를 수 있어요. 선택한 태그를 모두 반영하고, 조건이 맞는 내 검증 구조가 있으면 활용합니다.</p>
      {choice&&!tags.length&&<p className="draft-help">연습 태그를 하나 이상 선택해 주세요.</p>}
    </fieldset>
    {choice&&tags.length>0&&!resolved&&!selectionError&&<p role="status">선택한 조합의 규칙을 확인하고 있어요…</p>}
    {selectionError&&<p role="alert" className="notice error">{selectionError} <button className="secondary" onClick={()=>setSelectionAttempt(n=>n+1)}>조합 다시 확인</button></p>}
    {resolved&&<details className="generation-contract"><summary>출제 규칙: {resolved.title}</summary><p>{resolved.statement}</p></details>}
    <div className="generation-recommendations">
      <button className="secondary" disabled={busy||!!pending.current||!resolved||recommending} onClick={recommend}>{recommending?'규칙 찾는 중…':'다른 규칙 추천'}</button>
      <p className="draft-help">선택한 태그를 유지하면서 최근 12건에서 덜 만든 규칙을 추천해요. 추천에는 API 비용이 들지 않습니다.</p>
      {recommendationError&&<p role="alert" className="notice error">추천을 불러오지 못했어요: {recommendationError}</p>}
      {suggestions&&<div aria-live="polite">
        {!suggestions.alternativeAvailable&&<p className="draft-help">선택한 태그에서는 이 규칙만 지원해요. 다른 조합을 원하면 조건 태그를 줄여 주세요.</p>}
        {suggestions.alternativeAvailable&&<ul>{suggestions.suggestions.map(item=><li key={item.template}>
          <details><summary>{item.title} · 최근 {item.recentCount}회</summary><p>{item.statement}</p></details>
          <button className="secondary" disabled={busy||!!pending.current} onClick={()=>{setTags(item.tags);setSourceAnalysisId('');}} aria-label={item.title+' 적용'}>이 조합 적용</button>
        </li>)}</ul>}
      </div>}
    </div>
    <div className="generation-personalization"><label>반영할 풀이 분석<select aria-label="반영할 풀이 분석" value={sourceAnalysisId} disabled={busy||!!pending.current} onChange={e=>setSourceAnalysisId(e.target.value)}>
      <option value="">분석 없이 연습 포인트로 만들기</option>
      {learning.map((item,index)=><option key={item.id} value={item.id}>{index+1}. {item.summary.slice(0,80)}</option>)}
    </select></label>
    {sourceAnalysisId&&<p className="draft-help">{learning.find(item=>item.id===sourceAnalysisId)?.summary}</p>}
    <p className="draft-help">선택 사항 · 저장된 분석을 힌트·해설에 반영합니다. 추가 분석 비용은 없어요.</p>
    {!learning.length&&<p className="draft-help">제출 기록에서 {resolved?.title||'선택한 문제'} 풀이 분석을 완료하면 여기서 선택할 수 있어요.</p>}
    {learningError&&<p role="alert" className="notice error">분석 목록을 불러오지 못했어요: {learningError}</p>}
    </div><div className="generation-controls">
      <label className="check-row"><input type="checkbox" checked={shared} disabled={busy||!!pending.current} onChange={e=>setShared(e.target.checked)}/>검증 완료 후 다른 회원에게 공개</label><p className="draft-help">본문·예제·힌트·해설을 공유합니다. 내 제출 코드와 출제 요청은 공유하지 않아요.</p>
      <button className="primary" disabled={busy||!loaded||!resolved||!tags.length||(active&&!pending.current)} onClick={create}>{busy?'처리 중…':pending.current?'기존 생성 요청 확인':'내 연습 문제 만들기'}</button>
    </div>
    {active&&<p className="notice" role="status">출제가 진행 중입니다. 진행·결과에서 확인해 주세요. 완료 전까지 새 요청은 기다려 주세요.</p>}
    <p className="draft-help">소재 생성 API는 서비스 예산을 사용합니다. 검증 실패 시 최대 한 번 자동 수정합니다.</p>
    {error&&<p role="alert" className="notice error">{error}</p>}
    </section><section className="generation-results" aria-label="태그 생성 진행과 결과"><h3 id="tag-generation-results" tabIndex={-1}>진행·결과 <span className="muted">{loaded?jobs.length:''}</span></h3><p className="draft-help">검증이 끝나면 바로 풀 수 있어요. 기다리는 동안 다른 문제를 풀어도 됩니다.</p>
    {!loaded&&!error&&<p role="status">생성 기록을 불러오고 있어요…</p>}
    {!loaded&&error&&<button className="secondary" onClick={()=>refresh().catch(e=>setError(e.message))}>다시 불러오기</button>}
    {loaded&&jobs.length===0&&<p>아직 만든 문제가 없어요. 연습 포인트를 선택해 첫 문제를 만들어 보세요.</p>}
    <div aria-live="polite">{jobPaging.visible.map((job,index)=><details className="generation-job" key={job.id} open={(jobPaging.offset+index)===0||activeStates.includes(job.status)||job.status==='THEME_FAILED'}>
      <summary><strong>{job.artifacts?.title||'새 연습 문제'}</strong><span className="generation-status">{job.problemHeld?'문제 검토 중':job.status==='QUEUED'&&themeStates[job.theme?.status]||states[job.status]||job.status}</span></summary><div className="generation-job-body">
      <p className="draft-help">{job.preview?.contractTitle||(job.preview?.templateId==='parentheses-v1'?'올바른 괄호':'')}</p>
      {job.preview?.structure&&<p className="draft-help">{job.preview.structure.category} · {job.preview.structure.reused?'내 검증 구조 활용 · 본문과 힌트 새로 작성':'새 구조 작성'} · 모든 테스트 재검증</p>}
      {job.preview?.structure?.learningTags?.length>0&&<p className="draft-help">연습 태그: {job.preview.structure.learningTags.join(" · ")}</p>}
      {job.theme?.result&&<p className="draft-help">소재: {job.theme.result.setting}</p>}
      {job.status==='QUEUED'&&job.theme?.status==='HELD_BUDGET'&&<p>전체 API 예산이 부족해 소재 준비를 보류했어요. 기존 문제 풀이는 계속할 수 있습니다.</p>}
      {job.status==='QUEUED'&&job.theme?.status==='HELD_DISABLED'&&<p>테마 API가 비활성화되어 준비를 기다리고 있어요. 기존 문제 풀이는 계속할 수 있습니다.</p>}
      {['QUEUED','THEME_FAILED'].includes(job.status)&&['FAILED','UNKNOWN'].includes(job.theme?.status)&&<div>
        <p>소재 준비를 완료하지 못했어요. 다시 요청하면 추가 API 비용이 발생할 수 있습니다.</p>
        <button className="secondary" disabled={busy} onClick={()=>retryTheme(job)}>소재 준비 다시 요청</button>
        {job.theme.error&&<p className="draft-help">{job.theme.error}</p>}
      </div>}
      {job.artifacts?.context&&<p>{job.artifacts.context}</p>}
      {job.status==='READY'&&<><button className="primary" disabled={busy||job.problemHeld} onClick={()=>open(job)}>이 문제 풀기</button>
      <ProblemReview item={job} api={api} relatedStructures onHeld={held=>{setJobs(items=>items.map(item=>item.id===held.id?held:item));refresh().catch(e=>setError(e.message));}}/></>}
      {job.status==='AWAITING_REVIEW'&&<button className="secondary" disabled={busy} onClick={()=>resume(job)}>테스트 검증 시작</button>}
      {job.status==='FAILED'&&<p>정답과 테스트의 검증을 통과하지 못해 문제를 추가하지 않았어요. 새 문제를 요청할 수 있습니다.</p>}
      {job.error==='STRUCTURE_EVIDENCE_REVOKED'&&job.status!=='READY'&&<p className="notice">원본 검증 근거가 보류되어 이 문제의 게시도 중단했어요. 기존 기록은 유지됩니다.</p>}
      {job.error&&<details><summary>진행 정보</summary><p>{job.error}</p></details>}
    </div></details>)}</div><Pager paging={jobPaging} label="태그 출제 결과 페이지"/>
    </section></div></div>
  </section>;
}

export function AiBudget({api,visible=true}) {
  const [budget,setBudget]=useState(null),[error,setError]=useState('');
  useEffect(()=>{
    if(!visible)return;let stopped=false;
    async function load(){
      try{
        const status=await api('/api/ai/status');
        if(status.operator){const value=await api('/api/ai/budget');if(!stopped){setBudget(value);setError('');}}
      }catch(e){if(!stopped)setError(e.message);}
    }
    load();const timer=setInterval(load,30000);
    return()=>{stopped=true;clearInterval(timer);};
  },[visible]);
  if(!budget)return null;
  return <section className="ai-operations" aria-label="API 예산">
    <h3>API 예산</h3>
    <p>사용액 ${Number(budget.spentUsd).toFixed(4)} · 미정산 예약 ${Number(budget.reservedUsd).toFixed(4)} / 월 ${budget.limitUsd}</p>
    <p className="draft-help">전체 사용자 합산 · {budget.enabled&&budget.keyConfigured?'호출 활성':'호출 비활성'} · Codex 생성의 ChatGPT 구독료 제외</p>
    {budget.warning&&<p className="notice" role="alert">예산의 80% 이상을 사용하거나 예약했습니다.</p>}
    {error&&<p className="notice error" role="alert">{error}</p>}
  </section>;
}
