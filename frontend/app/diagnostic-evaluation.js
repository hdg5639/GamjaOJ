'use client';
import {useEffect,useRef,useState} from 'react';
import DiagnosticCorrection from './diagnostic-correction';
import DiagnosticPlan from './diagnostic-plan';
import DiagnosticCurriculum from './diagnostic-curriculum';
import DiagnosticProfile from './diagnostic-profile';
const tones={STRENGTH:'강점',WATCH:'주의',RISK:'위험'};
const names={STALE_EXPOSURE:'노출 정정 전 기록 · 해석 사용 중지',FACTS_ONLY:'판정 기록 저장됨',HELD_DISABLED:'AI 평가 사용 설정 대기',HELD_BUDGET:'평가 예산 대기',QUEUED:'평가 대기',RUNNING:'평가 중',COMPLETED:'평가 완료',UNKNOWN:'사용량 확인 필요',FAILED:'평가 실패',HELD_REVIEW:'문항 재검토 중',HIDDEN_DURING_ASSESSMENT:'진단 진행 중에는 해석을 숨깁니다'};
const outcomes={OPEN:'미완료 · 약점 판정 아님',PASSED:'통과',EXHAUSTED:'5회 소진',SKIPPED:'건너뜀 · 약점 판정 아님'};
export default function DiagnosticEvaluation({api,session,onOpen,onGeneration,onRuleDraft}) {
  const [rows,setRows]=useState([]),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const lock=useRef(false),version=useRef(0);
  const path=`/api/diagnostics/${session.id}/evaluations`;
  useEffect(()=>{let stopped=false;
    const refresh=async()=>{const revision=version.current;try{const data=await api(path);if(!stopped&&revision===version.current)setRows(data);}catch(e){if(!stopped)setError(e.message);}};
    refresh();const timer=setInterval(refresh,5000);return()=>{stopped=true;clearInterval(timer);};
  },[path,session.status]);
  async function request(){
    if(lock.current)return;lock.current=true;setBusy(true);setError('');version.current++;
    try{const saved=await api(path,{method:'POST'});setRows(old=>[saved,...old.filter(r=>r.id!==saved.id)]);}
    catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}
  }
  function showObservation(row,index){const node=document.getElementById(`diagnostic-observation-${row.id}-${index}`);node?.scrollIntoView({block:'start'});node?.focus();}
  const ready=session.items.some(i=>i.status!=='OPEN')&&!session.items.some(i=>i.pending>0);
  return <section aria-label="진단 평가"><h2>진단 평가</h2>
    <p>{session.status==='COMPLETED'?'요청하면 저장된 제출 근거로 AI 해석을 생성합니다. API 비용은 서비스 평가 예산에서 처리하며, 같은 근거의 결과는 재사용합니다.':'부분 결과는 완료 문항의 판정 기록만 저장합니다. 남은 문항에 힌트가 되지 않도록 AI 해석은 진단 종료 후 제공합니다.'}</p>
    <button className="primary" disabled={busy||!ready} onClick={request}>{busy?'요청 확인 중…':session.status==='COMPLETED'?'종합 평가 요청':'부분 판정 기록 저장'}</button>
    {!ready&&<p className="muted">완료한 문항이 있고 진행 중인 정식 채점이 없을 때 요청할 수 있어요.</p>}
    {error&&<p role="alert" className="notice error">{error}</p>}
    {rows.map((row,index)=><details key={row.id} open={index===0}><summary>{row.facts.complete?'종합':'부분'} 결과 · {names[row.status]||'상태 확인 중'}</summary>
      {row.status==='STALE_EXPOSURE'&&<p className="notice">이 기록은 노출 정정 이전의 자료입니다. 현재 기록으로 평가를 다시 요청해 주세요. 새 요청에 평가 가능한 제출이 있으면 AI를 사용합니다.</p>}
      <ul>{row.facts.items.map((item,n)=><li key={item.itemId}>{n+1}번 문항 · {item.externallySeen?'본 적 있음 · 평가 근거에서 제외':outcomes[item.status]} · 제출 {item.attempts}회</li>)}</ul>
      <p className="muted">선택하지 않은 분야는 미평가입니다. 하·중 문항 통과가 해당 분야 전체의 숙련을 뜻하지는 않습니다.</p>
      {index===0&&row.status!=='STALE_EXPOSURE'&&<DiagnosticProfile api={api} sessionId={session.id} row={row} onObservation={n=>showObservation(row,n)} onRuleDraft={onRuleDraft}/>}
      {row.interpretation&&<><DiagnosticCurriculum api={api} evaluationId={row.id}/><h3>AI 해석</h3><p>{row.interpretation.summary}</p><p>불확실성: {row.interpretation.uncertainty}</p>
        {row.interpretation.observations.map((o,n)=><article key={n} id={`diagnostic-observation-${row.id}-${n}`} tabIndex={-1}><h4>관찰 {n+1} · {o.confidence==='SUPPORTED'?'코드 근거 있음':'추가 확인 필요'}{o.tone?` · ${tones[o.tone]}`:''}</h4>
          {o.pattern&&<p><strong>코드 습관:</strong> {o.pattern}</p>}{o.risk&&<p><strong>{o.tone==='STRENGTH'?'유지할 이유':'위험해지는 경우'}:</strong> {o.risk}</p>}
          <pre aria-label="관찰의 코드 근거">{o.quote}</pre><p>{o.interpretation}</p><p>{o.nextAction==='ASSESS'?'추가 진단 제안':'연습 제안'}: {o.recommendation}</p>
          <small>근거 제출: {o.submissionId}</small><DiagnosticCorrection api={api} path={path} row={row} index={n} onSaved={saved=>{version.current++;setRows(old=>old.map(r=>r.id===saved.id?saved:r));}} /><DiagnosticPlan api={api} row={row} index={n} onOpen={onOpen} onGeneration={onGeneration} /></article>)}</>}
      {row.status==='UNKNOWN'&&<p>요청 처리 중 사용량이 확인되지 않았습니다. 자동으로 다시 호출하지 않습니다.</p>}
    </details>)}
  </section>;
}
