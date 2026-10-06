'use client';
import LoadingIndicator from './loading-indicator';
import GenerationProgress from './generation-progress';
import ProblemStatement from './problem-statement';
import ProblemReview from './problem-review';
import {useEffect,useRef,useState} from 'react';
import Pager,{usePage} from './pager';
const states={FINAL_QUEUED:'최종 검증 계획 대기',FINAL_GENERATING:'전수 영역·최대 입력 검토 중',FINAL_CHECKING:'Runner 최종 검증 중',FINAL_REJECTED:'검증 계획 보완 필요 · 미게시',FINAL_FAILED:'최종 검증 실패 · 미게시',PUBLISHED:'내 문제에 게시됨 · 실험 문제',REVIEW_QUEUED:'독립 검토 대기',REVIEW_GENERATING:'명세·경계 사례 독립 검토 중',REVIEW_CHECKING:'Runner 경계·잘못된 입력·오답 검사 중',REVIEW_CHECKED:'독립 검토·오답 검사 통과 · 미게시',REVIEW_REJECTED:'명세 보완 필요 · 미게시',REVIEW_FAILED:'독립 검토·오답 검사 실패',QUEUED:'초안 작성 대기',GENERATING:'명세 작성 중',DRAFT_READY:'초안 저장 완료 · 검증 전',FAILED:'초안 작성 실패',NEEDS_REVIEW:'작업 중단 · 상태 확인 필요',BUILD_QUEUED:'코드 작성 대기',BUILD_GENERATING:'정답·검증 코드 작성 중',CHECKING:'Runner 예제·입력 대조 중',CHECKED:'예비 실행 검사 통과 · 게시 전',BUILD_FAILED:'코드 작성·예비 검사 실패'};
const errors={NEEDS_CHATGPT_AUTH:'출제 서비스의 ChatGPT 로그인이 필요해요.',CODEX_TIMEOUT:'작성 제한 시간을 초과했어요.',CODEX_OUTPUT_LIMIT:'생성 결과가 크기 제한을 넘었어요.',CODEX_FAILED_CHECK_MODEL_OR_AUTH:'설정된 모델 호출에 실패했어요. 모델 접근 권한과 로그인 상태를 확인해야 합니다.',CODEX_VERSION_MISMATCH:'출제 서비스 버전을 확인해야 합니다.',CODEX_QUOTA_EXHAUSTED:'출제 모델 사용 한도에 도달했어요. 한도가 회복된 뒤 다시 시도해 주세요.',INVALID_SPEC_DRAFT:'명세 형식 검사를 통과하지 못했어요.',GENERATION_INTERRUPTED:'작성 중 연결이 끊겨 완료 여부를 확인해야 합니다.'};
const active=items=>items.some(item=>['QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'].includes(item.status));
export default function SpecDrafts({api,generationActive,onActive,onOpen,visible=true}) {
  const [items,setItems]=useState([]),[loaded,setLoaded]=useState(false),[error,setError]=useState(''),[loadError,setLoadError]=useState('');
  const [request,setRequest]=useState(''),[busy,setBusy]=useState(false);
  const [shared,setShared]=useState(true);
  const pending=useRef(null),revision=useRef(0);
  useEffect(()=>{if(!visible)return;let stopped=false;
    async function load(){const version=revision.current;try{const value=await api('/api/generation/spec-drafts');if(!stopped&&version===revision.current){setItems(value);setLoaded(true);setLoadError('');onActive(active(value));}}catch(e){if(!stopped&&version===revision.current)setLoadError(e.message);}}
    load();const timer=setInterval(load,5000);return()=>{stopped=true;clearInterval(timer);};
  },[visible]);
  async function create(){
    if(busy)return;revision.current++;setBusy(true);setError('');
    pending.current ||= {key:crypto.randomUUID(),request:request.trim(),shared};
    try {
      const value=await api('/api/generation/spec-drafts',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},body:JSON.stringify({request:pending.current.request,shared:pending.current.shared})});
      revision.current++;setItems(current=>[value,...current.filter(item=>item.id!==value.id)]);onActive(['QUEUED','GENERATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'].includes(value.status));
      pending.current=null;setRequest('');requestAnimationFrame(()=>document.getElementById('request-generation-results')?.focus());
    }catch(e){setError(e.message);if(e.status&&e.status<500)pending.current=null;}finally{setBusy(false);}
  }
  async function build(item,phase='build'){
    if(busy)return;revision.current++;setBusy(true);setError('');
    try{
      const value=await api('/api/generation/spec-drafts/'+item.id+'/'+phase,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({specHash:item.specHash})});
      revision.current++;setItems(current=>current.map(saved=>saved.id===value.id?value:saved));onActive(active([value]));
    }catch(e){setError(e.message);}finally{setBusy(false);}
  }
  const itemPaging=usePage(items,5);
  return <div className="spec-drafts generation-layout">
    <section className="generation-compose" aria-label="자유 출제 요청"><div className="generation-section-heading"><h3>원하는 문제를 설명해 주세요</h3><a href="#request-generation-results">진행·결과로 이동</a></div>
    <p className="draft-help">자유 출제 · 실험 기능. 알고리즘, 연습할 실수, 원하는 상황을 적어 주세요.</p>
    <label className="check-row"><input type="checkbox" checked={shared} disabled={busy||!!pending.current} onChange={e=>setShared(e.target.checked)}/>최종 검증·게시 후 다른 회원에게 공개</label><p className="draft-help">본문·예제·힌트·해설을 공유합니다. 내 제출 코드와 출제 요청은 공유하지 않아요.</p>
    <label htmlFor="spec-draft-request">원하는 문제</label>
    <textarea id="spec-draft-request" rows={7} maxLength={2000} value={request} disabled={busy||!!pending.current} onChange={e=>setRequest(e.target.value)} placeholder="예: DP로 풀되 같은 물건을 두 번 쓰면 틀리는 선택 문제. 작은 입력은 완전탐색으로 검증할 수 있게."/>
    <p className="draft-help">초안을 확인한 뒤 단계별 검증을 요청합니다. 최종 검증 전에는 풀이할 수 없어요. 요청과 결과는 본인만 볼 수 있습니다.</p>
    <button className="primary" disabled={busy||!loaded||(!pending.current&&(!request.trim()||generationActive||active(items)))} onClick={create}>{busy?'요청 중…':pending.current?'기존 초안 요청 확인':'문제 초안 작성'}</button>
    {(generationActive||active(items))&&<p className="draft-help">진행 중인 출제 작업을 마친 뒤 새 요청을 할 수 있어요.</p>}
    <ol className="generation-guide" aria-label="자유 출제 순서"><li>요청 작성 <span>원하는 연습 내용 전달</span></li><li>초안 확인 <span>명세를 읽고 코드 작성 요청</span></li><li>단계별 검증 <span>의미·오답 검사 후 최종 게시</span></li></ol>
    </section><section className="generation-results" aria-label="자유 출제 진행과 결과"><h3 id="request-generation-results" tabIndex={-1}>진행·결과 <span className="muted">{loaded?items.length:''}</span></h3><p className="draft-help">현재 상태를 확인하고 다음 단계로 진행하세요.</p>
    {loaded&&!items.length&&<p className="generation-empty">아직 요청한 문제가 없어요. 원하는 문제를 적어 첫 초안을 만들어 보세요.</p>}
    {loadError&&<p className="notice error" role="alert">초안 기록을 불러오지 못했어요. 잠시 후 다시 확인합니다. {loadError}</p>}
    {error&&<p className="notice error" role="alert">{error}</p>}
    {!loaded&&!error&&<LoadingIndicator>초안 기록을 불러오고 있어요…</LoadingIndicator>}
    <div aria-live="polite">{itemPaging.visible.map((item,index)=><details className="generation-job" key={item.id} open={(itemPaging.offset+index)===0||active([item])||['DRAFT_READY','CHECKED','REVIEW_CHECKED'].includes(item.status)}>
      <summary><strong>{item.spec?.title||'새 문제 초안'}</strong><span className="generation-status">{item.problemHeld?'문제 검토 중 · 새 풀이 보류':states[item.status]||item.status}</span></summary><div className="generation-job-body">
      <GenerationProgress recovery={item.recovery} resource={item.resources}/>
      <p className="draft-help">요청: {item.request}</p>
      {item.error&&<p>{errors[item.error]||'작성 또는 예비 검사를 완료하지 못했어요.'} 기존 요청은 기록에 남아 있습니다.</p>}
      {item.status==='DRAFT_READY'&&<button className="secondary" disabled={busy||!!pending.current||generationActive||active(items)} onClick={()=>build(item)}>코드 작성·예비 검사</button>}
      {item.status==='CHECKED'&&<button className="secondary" disabled={busy||!!pending.current||generationActive||active(items)} onClick={()=>build(item,'review')}>의미·오답 검증</button>}
      {item.status==='CHECKED'&&<p className="draft-help">예제와 생성 입력 4개에서 정답·oracle을 대조했습니다. 전수·오답 구분·최종 게시 검사는 아직 남아 있어 풀 수 없습니다.</p>}
      {item.status==='REVIEW_CHECKED'&&<button className="secondary" disabled={busy||!!pending.current||generationActive||active(items)} onClick={()=>build(item,'publish')}>최종 검증·내 문제로 게시</button>}
      {item.status==='PUBLISHED'&&!item.problemHeld&&<button className="primary" disabled={busy} onClick={async()=>{setBusy(true);setError('');try{await onOpen(item.problemVersion);}catch(e){setError(e.message);}finally{setBusy(false);}}}>이 문제 풀기</button>}
      {item.status==='PUBLISHED'&&<ProblemReview item={item} api={api} onHeld={value=>{revision.current++;setItems(current=>current.map(saved=>saved.id===value.id?value:saved));}}/>}
      {item.publication&&<div>
        {item.status==='PUBLISHED'&&<p className="draft-help">선언한 작은 영역 {item.publication.domainCases}개 전수 대조 · 최종 Runner {item.publication.executions}개 작업 통과. 전체 입력의 정답을 증명한 것은 아닙니다.</p>}
        {item.publication.issues?.length>0&&<ul>{item.publication.issues.map((issue,index)=><li key={index}>{issue}</li>)}</ul>}
      </div>}
      {item.review&&<div>
        <p className="draft-help">독립 검토 Runner 작업 {item.review.executions}개 완료{item.status!=='PUBLISHED'&&' · 미게시'}</p>
        {item.review.issues?.length>0&&<ul>{item.review.issues.map((issue,index)=><li key={index}>{issue}</li>)}</ul>}
        {item.status==='REVIEW_CHECKED'&&<p className="draft-help">경계 사례·잘못된 입력과 오답 코드 2개를 검사했습니다. 작은 영역 전수·실행 상한·최종 독립 입력 검사가 남아 있습니다.</p>}
      </div>}
      {item.checks&&<p className="draft-help">Runner 작업 {item.checks.executions}개 완료{item.status!=='PUBLISHED'&&' · 미게시'}</p>}
      {item.spec&&<details><summary>명세와 검증 계획 보기</summary>
        <p>{item.spec.category} · {item.spec.tags.join(' · ')}</p>
        <ProblemStatement statement={item.spec.statement}/>
        {Object.entries({inputDefinition:'입력',outputDefinition:'출력',constraints:'제약',referenceStrategy:'기준 풀이 전략',oracleStrategy:'독립 oracle 계획'}).map(([key,label])=><div key={key}><h4>{label}</h4><p className="spec-text">{item.spec[key]}</p></div>)}
        <h4>{item.checks?'예제 · 예비 실행 검사 완료':'예제 · 아직 실행 검증되지 않음'}</h4>
        {item.spec.samples.map((sample,index)=><div key={index}><p>예제 {index+1} 입력</p><pre>{sample.input}</pre><p>출력</p><pre>{sample.output}</pre><p>{sample.explanation}</p></div>)}
        <h4>경계값 검사 계획</h4><ul>{item.spec.boundaryClasses.map((value,index)=><li key={index}>{value}</li>)}</ul>
        <h4>오답 구분 계획</h4><ul>{item.spec.mutantIdeas.map((value,index)=><li key={index}>{value}</li>)}</ul>
      </details>}
    </div></details>)}</div><Pager paging={itemPaging} label="자유 출제 결과 페이지"/>
    </section></div>;
}
