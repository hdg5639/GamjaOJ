'use client';
import FollowupGoal from './followup-goal';
import { useEffect, useState } from 'react';
import ProblemReflection from './problem-reflection';

const qualityQuestion='코드 품질 회고: 이 제출의 알고리즘 선택, 시간·공간 복잡도, 가독성과 구조, 경계 조건 처리를 짧게 검토하고 코드 근거를 제시해 주세요. 더 간단하게 고칠 부분과 다음에 혼자 다시 풀기 위한 연습 한 가지를 제안해 주세요. 기억력이나 실력을 단정하지 마세요.';

const states = {QUEUED:'분석 대기',RUNNING:'분석 중',HELD_DISABLED:'AI 호출이 일시 중지되어 있어요.',HELD_BUDGET:'서비스 전체 API 예산이 부족해 보류했어요.',UNKNOWN:'응답을 확인하지 못했어요. 재시도는 추가 비용이 발생할 수 있어요.',FAILED:'분석을 완료하지 못했어요.',COMPLETED:'분석 완료'};
export default function AiFeedback({ submission, api, review=false, compact=false, onReflectionSaved }) {
  const [items,setItems]=useState([]),[question,setQuestion]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [operator,setOperator]=useState(false),[loaded,setLoaded]=useState(false);
  useEffect(()=>{let stopped=false;
    const refresh=()=>api(`/api/ai/tasks?submissionId=${submission.id}`).then(data=>{if(!stopped){setItems(data);setLoaded(true);}}).catch(e=>{if(!stopped)setError(e.message);});
    refresh();api('/api/ai/status').then(data=>{if(!stopped)setOperator(!!data.operator);}).catch(()=>{});
    window.addEventListener('focus',refresh);
    const timer=items.some(item=>['QUEUED','RUNNING'].includes(item.status))?setInterval(refresh,5000):null;
    return()=>{stopped=true;if(timer)clearInterval(timer);window.removeEventListener('focus',refresh);};
  },[submission.id,items.some(item=>['QUEUED','RUNNING'].includes(item.status))]);
  async function request(kind,strong=false) {
    setBusy(true);setError('');
    try {await api('/api/ai/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({submissionId:submission.id,kind,question:kind==='HINT'?question:review?qualityQuestion:'',strong})});setItems(await api(`/api/ai/tasks?submissionId=${submission.id}`));}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  async function retry(id) {
    setBusy(true);setError('');try {await api(`/api/ai/tasks/${id}/retry`,{method:'POST'});setItems(await api(`/api/ai/tasks?submissionId=${submission.id}`));}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  const held=submission.problemHeld||items.some(item=>item.problemHeld);
  if(submission.status!=='FINISHED'||submission.verdict==='IE')return null;
  const reflection=submission.verdict==='AC'&&!held&&!submission.diagnosticItemId&&submission.input==null
    ?<ProblemReflection key={submission.id} submission={submission} api={api} onSaved={onReflectionSaved}/>:null;
  const hint=<><label>추가로 궁금한 점<textarea rows={2} value={question} maxLength={1000} onChange={e=>setQuestion(e.target.value)} placeholder="이 코드에서 합계가 틀리는 이유가 궁금해요" /></label>
    <button className="secondary" disabled={busy||held||!question.trim()} onClick={()=>request('HINT')}>맞춤 힌트 요청</button></>;
  function entry(item){
    const result=item.result;
    const detail=result&&<><p>{result.summary}</p><h4>코드에서 확인한 점</h4><ul>{result.observations.map((text,i)=><li key={i}>{text}</li>)}</ul><h4>다음에 해볼 것</h4><ul>{result.nextSteps.map((text,i)=><li key={i}>{text}</li>)}</ul><p className="muted">{result.uncertainty}</p></>;
    const followup=!review&&item.kind==='ANALYSIS'&&item.status==='COMPLETED'&&result&&<FollowupGoal analysis={item} api={api} disabled={held||busy}/>;
    return <article key={item.id} className="feedback-entry">
      <h4>{item.kind==='HINT'?'맞춤 힌트':'풀이 분석'} · {item.problemHeld&&item.status!=='COMPLETED'?'문제 검토 중 · 분석 보류':states[item.status]||item.status}</h4>
      {compact? <>
        {result&&<><p className="feedback-summary">{result.summary}</p><details className="feedback-analysis-detail"><summary>분석 자세히 보기</summary>{detail}</details></>}
        {followup&&<details className="feedback-followup"><summary>이 분석으로 이어서 연습</summary>{followup}</details>}
        <details className="feedback-model"><summary>분석 정보{item.errorCode?' · '+item.errorCode:''}</summary><p className="draft-help">{item.model} · {item.effort}{item.errorCode&&` · ${item.errorCode}`}</p></details>
      </>:<><p className="draft-help">{item.model} · {item.effort}{item.errorCode&&` · ${item.errorCode}`}</p>{detail}{followup}</>}
      {['FAILED','UNKNOWN'].includes(item.status)&&<button className="secondary" disabled={busy||held} onClick={()=>retry(item.id)}>추가 비용으로 재시도</button>}
    </article>;
  }
  return <section className={'ai-feedback'+(compact?' feedback-compact':'')} aria-label="개인 학습 피드백">
    {!compact&&reflection}
    <h4>{review?'코드 품질과 다음 연습':compact?'이 제출의 학습 피드백':'개인 학습 피드백'}</h4>
    {held&&<p className="notice">문제 검토 중 · 이전 피드백은 참고 기록이며 학습 판단과 다음 출제의 근거에서 제외됩니다. 새 분석은 보류됩니다.</p>}
    <p className="draft-help">{compact?'필요할 때 AI 분석을 요청해요. 같은 분석은 재사용하며 채점 결과는 바뀌지 않아요.':review?'알고리즘·복잡도·가독성·경계 조건을 짧게 살펴봐요. 버튼을 누르면 GPT API 분석을 요청하고, 같은 요청은 저장된 결과를 재사용해요.':'저장된 이 제출을 분석해요. 학습 피드백은 채점 판정을 바꾸지 않으며, 같은 분석은 재사용합니다.'}</p>
    <div className="editor-tools"><button className="secondary" disabled={busy||held} onClick={()=>request('ANALYSIS')}>{busy?'요청 중…':review?'빠른 코드 분석':'풀이 분석 요청'}</button>
      {operator&&<button className="secondary" disabled={busy||held} onClick={()=>request('ANALYSIS',true)}>상위 모델로 재분석</button>}</div>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {compact? <>
      {loaded&&!error&&!items.length&&<p className="muted feedback-empty">아직 분석 기록이 없어요. 풀이 분석을 요청하거나 궁금한 점을 질문해 보세요.</p>}
      {!loaded&&!error&&<p role="status">분석 기록을 불러오는 중…</p>}
      {items[0]&&entry(items[0])}
      {!review&&<details className="feedback-question"><summary>추가 질문·맞춤 힌트</summary>{hint}</details>}
      {reflection&&<details className="feedback-reflection"><summary>풀이 자신감·회고 남기기</summary>{reflection}</details>}
      {items.length>1&&<details className="feedback-history"><summary>지난 분석·힌트 ({items.length-1})</summary>{items.slice(1).map(item=><details key={item.id} className="feedback-past-entry"><summary>{item.kind==='HINT'?'맞춤 힌트':'풀이 분석'} · {states[item.status]||item.status}{item.createdAt?' · '+new Date(item.createdAt).toLocaleString('ko-KR'):''}</summary>{entry(item)}</details>)}</details>}
    </>:<>{!review&&hint}{items.map(entry)}</>}
  </section>;
}
