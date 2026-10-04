'use client';
import ProblemStatement from './problem-statement';
import SelectControl from './select-control';
import {useRef,useState} from 'react';
export default function DiagnosticPlan({api,row,index,onOpen,onGeneration,sourceKind="CODE_OBSERVATION"}) {
  const [options,setOptions]=useState(null),[plans,setPlans]=useState([]),[goal,setGoal]=useState(''),[selected,setSelected]=useState({}),[rule,setRule]=useState({});
  const [busy,setBusy]=useState(false),[error,setError]=useState('');const lock=useRef(false),pending=useRef(null);
  const base='/api/diagnostic-plans',query=`evaluationId=${row.id}&observationIndex=${index}&sourceKind=${sourceKind}`;
  async function load(){if(lock.current)return;lock.current=true;setBusy(true);setError('');try{
    const [value,saved]=await Promise.all([api(`${base}/options?${query}`),api(`${base}?evaluationId=${row.id}`)]);
    setOptions(value);setPlans(saved.filter(p=>p.observationIndex===index&&(p.sourceKind||'CODE_OBSERVATION')===sourceKind));
    setGoal(old=>old||saved.find(p=>p.observationIndex===index&&(p.sourceKind||'CODE_OBSERVATION')===sourceKind)?.goal||(value.observation.recommendation.length<=120?value.observation.recommendation:''));
  }catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}}
  async function confirm(event){event.preventDefault();if(lock.current)return;lock.current=true;setBusy(true);setError('');
    pending.current ||= {key:crypto.randomUUID(),body:{evaluationId:row.id,observationIndex:index,reviewHash:options.reviewHash,goal,...(sourceKind==='SELF_REPORT'?{sourceKind}: {})}};
    try{const plan=await api(base,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},body:JSON.stringify(pending.current.body)});
      pending.current=null;setPlans(old=>[plan,...old.filter(p=>p.id!==plan.id)]);window.dispatchEvent(new Event('gamjaoj-plan-changed'));
    }catch(e){if(e.status>=400&&e.status<500)pending.current=null;setError(e.message);}finally{lock.current=false;setBusy(false);}}
  async function start(plan,target){if(lock.current)return;lock.current=true;setBusy(true);setError('');try{
    const version=target||plan.problemVersion||selected[plan.id];
    const saved=await api(`${base}/${plan.id}/start`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({problemVersion:version})});
    setPlans(old=>old.map(p=>p.id===saved.id?saved:p));window.dispatchEvent(new Event('gamjaoj-plan-changed'));await onOpen(saved.problemVersion);
  }catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}}
  async function action(plan,kind,body){if(lock.current)return;lock.current=true;setBusy(true);setError('');try{
    const saved=await api(`${base}/${plan.id}/${kind}`,{method:'POST',headers:{'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
    setPlans(old=>old.some(p=>p.id===saved.id)?old.map(p=>p.id===saved.id?saved:p):[...old,saved]);window.dispatchEvent(new Event('gamjaoj-plan-changed'));
  }catch(e){setError(e.message);}finally{lock.current=false;setBusy(false);}}
  const alreadySaved=plans.some(p=>p.goal===goal&&['READY','ACTIVE','TRAINING_ENDED','AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(p.status));
  return <div className="diagnostic-plan">
    <button className="secondary" disabled={busy||!!pending.current} onClick={load}>{options?'최신 의견·학습 계획 다시 확인':'이 제안으로 학습 계획 준비'}</button>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {options&&<>
      {options.corrections.length>0&&<><p>이 관찰에는 정정 의견이 있습니다. 아래 설명을 함께 확인하고 본인의 목표를 정해 주세요.</p><ul>{options.corrections.map(c=><li key={c.id}>{c.note}</li>)}</ul></>}
      {options.observation.nextAction==='ASSESS'?<p>이 제안은 부족함을 확정한 연습 처방이 아니라 추가 확인입니다. 다른 진단 보기에서 원하는 분야를 선택해 주세요.</p>:<>
        <form onSubmit={confirm}><label>내가 확인한 연습 목표<input required maxLength={120} value={goal} disabled={busy||!!pending.current} onChange={e=>setGoal(e.target.value)}/></label>
          <p className="muted">제안과 정정 의견을 검토한 뒤 목표를 수정하고, 여기서 문제나 생성 규칙을 직접 고를 수 있어요. 학습 계획 화면으로 돌아가면 적합한 문제 연결과 필요한 AI 생성·검증이 자동으로 진행돼요.</p>
          <button className="secondary" disabled={busy||!goal.trim()||alreadySaved}>{alreadySaved?'목표 저장됨':pending.current?'같은 목표 저장 다시 확인':'이 목표를 내 학습 계획에 저장'}</button></form>
        {plans.map(plan=><div key={plan.id}><h4>{plan.roundNumber||1}회차 · {plan.goal||'확인할 수 없는 학습 계획'}</h4>
          {plan.status==='READY'&&<div>
            {!plan.generationId?<>
              {options.rules?.length>0&&<div className="diagnostic-rule-generation"><label>검증된 규칙으로 바로 만들기<SelectControl value={rule[plan.id]||''} disabled={busy} onChange={e=>setRule({...rule,[plan.id]:e.target.value})}><option value="">규칙을 선택하세요</option>{[...options.rules].sort((a,b)=>(options.matchingRules||[]).includes(b.id)-(options.matchingRules||[]).includes(a.id)).map(r=><option key={r.id} value={r.id}>{(options.matchingRules||[]).includes(r.id)?'[진단 분야 일치] ':''}{r.label}</option>)}</SelectControl></label>
                <p className="muted">이 목표와 규칙이 맞는지는 직접 확인해 주세요. [진단 분야 일치]는 분야 이름이 같다는 뜻이며 검증된 추천이 아닙니다. 규칙을 고정해 새 본문으로 만들고, 모든 실행 검증과 최종 검토를 통과해야 풀 수 있습니다{options.rules.find(r=>r.id===rule[plan.id])?.verifiedReference?' · 검증된 정답 코드를 다시 사용합니다':''}.</p>
                <button className="secondary" disabled={busy||!rule[plan.id]} onClick={()=>action(plan,'generate',{ruleVersionId:rule[plan.id]})}>선택한 규칙으로 문제 생성</button></div>}
              <p>맞는 문제가 없다면 확정한 목표로 새 문제 초안을 요청할 수 있어요. 생성 모델을 사용하며, 초안 확인과 기존 검증 단계를 거쳐야 풀 수 있습니다.</p><button className="secondary" disabled={busy} onClick={()=>action(plan,'generate')}>이 목표로 맞춤 문제 생성 요청</button></>:
              <><p>이 목표의 생성 요청을 저장했습니다. 초안과 검증 진행은 생성 화면에서 확인하세요.</p><button className="secondary" disabled={busy} onClick={onGeneration}>생성·검증 화면으로</button></>}
            {plan.generatedVersion&&<><p>이 계획에서 요청한 문제의 게시 검증이 완료됐어요. 목표 적합성은 문제 설명을 읽고 확인해 주세요.</p><button disabled={busy} className="primary" onClick={()=>start(plan,plan.generatedVersion)}>생성된 문제로 훈련 시작</button></>}
          </div>}
          {plan.status==='TRAINING_ENDED'&&!plan.reviewedSubmissionId&&<div><p>마지막 정식 제출이 정답이라면 풀이 과정에서 도움을 받았는지 남겨 주세요.</p><button disabled={busy} onClick={()=>action(plan,'reflect',{usedHelp:false})}>도움 없이 풀었어요</button><button disabled={busy} onClick={()=>action(plan,'reflect',{usedHelp:true})}>도움을 받아 풀었어요</button></div>}
          {plan.reviewedSubmissionId&&<p className="notice">{plan.usedHelp?'도움을 받은 AC로 기록했습니다.':'본인이 보고한 도움 없는 AC로 기록했습니다.'} 새로운 문제에서의 재평가나 숙련도 인증을 뜻하지 않습니다.</p>}
          {['TRAINING_ENDED','AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(plan.status)&&!plans.some(p=>p.previousPlanId===plan.id)&&<div>
            <p>위의 최신 의견을 확인했다면 같은 목표로 다음 회차를 준비할 수 있어요. 이전 기록은 보존되며, 정답을 얻지 못했어도 이어서 연습할 수 있습니다.</p>
            <p className="muted">문제는 다음 회차에서 직접 선택하거나 별도로 생성합니다. 같은 문제 반복은 새로운 문제에서의 실력 향상 근거가 아닙니다.</p>
            <button className="secondary" disabled={busy} onClick={()=>action(plan,'next-round',{reviewHash:options.reviewHash})}>최신 의견 확인 · 다음 회차 준비</button>
          </div>}
          {plan.status==='READY'?<><label>직접 고를 연습 문제<SelectControl value={selected[plan.id]||''} disabled={busy} onChange={e=>setSelected({...selected,[plan.id]:e.target.value})}><option value="">문제를 선택하세요</option>{options.problems.map(p=><option key={p.version} value={p.version}>{p.title}</option>)}</SelectControl></label>
            <p className="muted">현재 풀 수 있는 일반 문제 목록입니다. 이 목표와의 적합성이 자동 검증된 추천은 아닙니다.</p>
            {selected[plan.id]&&<ProblemStatement statement={options.problems.find(p=>p.version===selected[plan.id])?.statement} version={selected[plan.id]} api={api} manage={false}/>}
            <button className="primary" disabled={busy||!selected[plan.id]} onClick={()=>start(plan)}>선택한 문제로 훈련 시작</button></>:
            plan.sessionId?<button className="secondary" disabled={busy||plan.status==='HELD'} onClick={()=>start(plan)}>{plan.status==='TRAINING_ENDED'?'연습 문제 다시 보기':'시작한 훈련 이어서 보기'}</button>:
            <p>{plan.status==='NEEDS_REVIEW'?'저장 후 정정 의견이 변경됐어요. 최신 내용을 읽고 목표를 다시 확정해 주세요.':'진단이나 근거 재검토가 끝난 뒤 확인할 수 있어요.'}</p>}
        </div>)}
      </>}
    </>}
  </div>;
}
