'use client';
import {useEffect,useRef,useState} from 'react';
import DiagnosticCorrection from './diagnostic-correction';
import DiagnosticPlan from './diagnostic-plan';
import DiagnosticCurriculum from './diagnostic-curriculum';
import DiagnosticProfile from './diagnostic-profile';
import {categoryLabels,bankTitle} from './diagnostic-categories';
import DiagnosticRoadmap from './diagnostic-roadmap';
import {diagnosticOutcome} from './diagnostic-outcomes';
const tones={STRENGTH:'강점',WATCH:'주의',RISK:'위험'};
const names={STALE_EXPOSURE:'정정 전 기록',FACTS_ONLY:'판정 기록 저장됨',HELD_DISABLED:'AI 평가 설정 대기',HELD_BUDGET:'평가 예산 대기',QUEUED:'평가 대기',RUNNING:'평가 중',COMPLETED:'평가 완료',UNKNOWN:'처리 결과 확인 필요',FAILED:'평가 실패',HELD_REVIEW:'문항 재검토 중',HIDDEN_DURING_ASSESSMENT:'진단 종료 후 확인 가능'};
const outcomes={PASSED:'통과',EXHAUSTED:'5회 소진',SKIPPED:'건너뜀',OPEN:'미완료'};
export default function DiagnosticEvaluation({api,session,onOpen,onGeneration,onRuleDraft,onAssess}) {
  const [rows,setRows]=useState([]),[busy,setBusy]=useState(false),[error,setError]=useState(''),[loaded,setLoaded]=useState(false),[selected,setSelected]=useState(''),[visited,setVisited]=useState([]);
  const lock=useRef(false),version=useRef(0);
  const path=`/api/diagnostics/${session.id}/evaluations`;
  useEffect(()=>{let stopped=false;
    const refresh=async()=>{const revision=version.current;try{const data=await api(path);if(!stopped&&revision===version.current){setRows(data);setLoaded(true);}}catch(e){if(!stopped){setError(e.message);setLoaded(true);}}};
    refresh();const timer=setInterval(refresh,5000);return()=>{stopped=true;clearInterval(timer);};
  },[path,session.status]);
  async function request(){
    if(lock.current)return;lock.current=true;setBusy(true);setError('');version.current++;
    try{const saved=await api(path,{method:'POST'});setRows(old=>[saved,...old.filter(r=>r.id!==saved.id)]);choose(saved.id);setLoaded(true);}
    catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}
  }
  const row=rows.find(r=>r.id===selected)||rows[0];
  function choose(id){if(row)setVisited(old=>Array.from(new Set([...old,row.id])));setSelected(id);}
  const activeId=row?.id;
  const complete=session.status==='COMPLETED';
  const ready=session.items.some(i=>i.status!=='OPEN')&&!session.items.some(i=>i.pending>0);
  const facts=row?.facts.items||session.items;
  const pending=rows.some(r=>r.status==='QUEUED'||r.status==='RUNNING');
  function showObservation(index){const node=document.getElementById(`diagnostic-observation-${row.id}-${index}`);if(node){const details=node.querySelector('details.diagnostic-observation-detail');if(details)details.open=true;node.scrollIntoView({block:'start'});node.focus();}}
  return <section className="diagnostic-report" aria-label="진단 평가">
    <header className="diagnostic-report-heading"><div><p className="report-kicker">LEARNING REPORT · {bankTitle(session.bankId||'')}</p><h2>{complete?'진단 결과':'진행 기록 저장'}</h2><p className="muted">{complete?'어떤 문제를 풀었고, 코드에서 어떤 습관이 보였는지 확인하세요.':'완료한 문항의 판정만 저장합니다. AI 해석은 진단 종료 후 볼 수 있어요.'}</p></div>
      <button className={row?'secondary':'primary'} disabled={busy||!ready||pending} onClick={request}>{busy?'요청 확인 중…':pending?'평가 진행 중…':complete?'종합 평가 요청':'부분 판정 기록 저장'}</button>
    </header>
    {complete&&<p className="diagnostic-report-caption">진단을 마쳤어요. 평가 요청 시 서비스 AI 예산을 사용하며, 같은 제출 근거의 결과는 재사용합니다.</p>}
    {!ready&&<p className="muted">완료한 문항이 있고 진행 중인 정식 채점이 없을 때 요청할 수 있어요.</p>}
    {error&&<p role="alert" className="notice error">{error}</p>}
    {!loaded&&<p role="status">평가 기록을 불러오고 있어요…</p>}
    {rows.length>1&&<label className="diagnostic-history-choice">평가 기록<select aria-label="평가 기록" value={row.id} onChange={e=>choose(e.target.value)}>{rows.map((r,i)=><option key={r.id} value={r.id}>{i===0?'최근 · ':''}{r.facts.complete?'종합 결과':'중간 기록'} · {names[r.status]||'상태 확인 중'} · {r.facts.items.filter(item=>item.status!=='OPEN').length}문항{r.createdAt?` · ${new Date(r.createdAt).toLocaleString('ko-KR')}`:` · 기록 ${rows.length-i}`}</option>)}</select></label>}
    <nav className="diagnostic-report-index" aria-label="보고서 목차">{[['diagnostic-report-overview','결과 요약'],['diagnostic-report-roadmap','추천 커리큘럼'],...(row?[[`diagnostic-profile-${row.id}`,'분야별 결과']]:[]),...(row?.interpretation?[[`diagnostic-evidence-${row.id}`,'코드 근거']]:[])].map(([id,label])=><button key={id} onClick={()=>{const node=document.getElementById(id);if(node){node.tabIndex=-1;node.scrollIntoView({block:'start'});node.focus({preventScroll:true});}}}>{label}</button>)}</nav>
    <dl id="diagnostic-report-overview" className="diagnostic-result-counts" aria-label="문항 결과 요약">{Object.entries(outcomes).map(([status,label])=><div key={status} data-outcome={status}><dt>{label}</dt><dd>{facts.filter(i=>i.status===status).length}<span>문항</span></dd></div>)}</dl>
    {loaded&&!row&&<div className="diagnostic-report-empty"><h3>{complete?'판정 기록은 준비됐어요':'현재 진행 상황을 남겨 두세요'}</h3><p>{complete?'종합 평가를 요청하면 제출 코드를 바탕으로 분야별 관찰과 다음 연습 방향을 정리합니다.':'중간 기록은 저장 시점의 결과입니다. 저장 후에도 진단을 계속 풀 수 있어요.'}</p></div>}
    {loaded&&!row&&complete&&<div id="diagnostic-report-roadmap"><DiagnosticRoadmap api={api} sessionId={session.id} items={facts} onOpen={onOpen} onAssess={onAssess}/></div>}
    {rows.filter(r=>r.id===row?.id||visited.includes(r.id)).map(row=>{return <div className="diagnostic-report-content" key={row.id} hidden={row.id!==activeId}>
      <div className="diagnostic-result-meta"><strong>{row.facts.complete?'종합 평가':'중간 기록'}</strong><span role="status">{names[row.status]||'상태 확인 중'}</span></div>
      {row.status==='STALE_EXPOSURE'&&<p className="notice">노출 정정 전 기록입니다. 현재 제출 근거로 평가를 다시 요청해 주세요.</p>}
      {row.interpretation&&<section className="diagnostic-summary" aria-label="AI 해석"><h3><span className="report-section-number">01</span> 이번 진단에서 보인 점</h3><p className="diagnostic-summary-text">{row.interpretation.summary}</p><p className="muted">해석 범위 · {row.interpretation.uncertainty}</p></section>}
      {row.status==='QUEUED'||row.status==='RUNNING'?<p className="notice" role="status">제출 코드를 검토하고 있어요. 이 화면에서 결과가 자동으로 갱신됩니다.</p>:null}
      <p className="diagnostic-report-caption">접근 어려움은 본인 보고, 코드 관찰은 제출 근거로 구분합니다. 시간 부족·사유 미상은 미확인이며 일부 통과가 분야 전체의 숙련을 뜻하지는 않아요.</p>
      {row.status!=='STALE_EXPOSURE'&&<div id={row.id===activeId?'diagnostic-report-roadmap':undefined}><DiagnosticRoadmap api={api} sessionId={session.id} items={row.facts.items} row={row} onObservation={showObservation} onOpen={onOpen} onAssess={onAssess}/>{row.interpretation&&<DiagnosticCurriculum api={api} evaluationId={row.id}/>}</div>}
      {row.status!=='STALE_EXPOSURE'&&<DiagnosticProfile api={api} sessionId={session.id} row={row} onObservation={showObservation} onRuleDraft={onRuleDraft}/>}
      {!row.interpretation&&<div className="diagnostic-fact-table"><table><caption>저장된 문항별 판정</caption><thead><tr><th>문항</th><th>분야</th><th>결과</th><th>제출</th></tr></thead><tbody>{row.facts.items.map((item,n)=><tr key={item.itemId}><th scope="row">{n+1}번</th><td>{categoryLabels[item.category]||'진단 문항'}</td><td>{diagnosticOutcome(item)}</td><td>{item.attempts}회</td></tr>)}</tbody></table></div>}
      {row.interpretation&&<section id={`diagnostic-evidence-${row.id}`} className="diagnostic-observations" aria-label="코드 관찰"><h3><span className="report-section-number">04</span> 코드에서 확인한 근거</h3>{row.interpretation.observations.map((o,n)=><article className="diagnostic-observation" data-tone={o.tone||'NONE'} key={n} id={`diagnostic-observation-${row.id}-${n}`} tabIndex={-1}>
        <header><span className="diagnostic-observation-number">{String(n+1).padStart(2,'0')}</span><div><p className="diagnostic-observation-meta">{o.tone?`${tones[o.tone]} · `:''}{o.confidence==='SUPPORTED'?'코드 근거 있음':'추가 확인 필요'}</p><h4>{o.pattern||`관찰 ${n+1}`}</h4></div></header>
        <p>{o.interpretation}</p>{o.risk&&<p><strong>{o.tone==='STRENGTH'?'유지할 이유':'주의할 상황'}</strong> · {o.risk}</p>}
        <p className="diagnostic-next-action">{o.nextAction==='ASSESS'?'추가 진단 제안':'연습 제안'}: {o.recommendation}</p>
        <details className="diagnostic-observation-detail"><summary>코드 근거·정정·학습 계획</summary><pre tabIndex={0} aria-label="관찰의 코드 근거">{o.quote}</pre>
        <small className="diagnostic-evidence-id">근거 제출: {o.submissionId}</small>
        <DiagnosticCorrection api={api} path={path} row={row} index={n} onSaved={saved=>{version.current++;setRows(old=>old.map(r=>r.id===saved.id?saved:r));}} />
        <DiagnosticPlan api={api} row={row} index={n} onOpen={onOpen} onGeneration={onGeneration} /></details>
      </article>)}</section>}
      {row.status==='UNKNOWN'&&<p className="notice">처리 결과와 사용량을 확인하지 못했습니다. 중복 호출을 막기 위해 자동으로 다시 요청하지 않습니다.</p>}
    </div>;})}
  </section>;
}
