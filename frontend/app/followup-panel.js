'use client';
import {useEffect,useState} from 'react';
const labels={HELD:'문제 검토 중 · 확인 보류',READY_TO_PRACTICE:'다음 문제 선택',ACTIVE:'훈련 진행 중',WAITING_JUDGE:'채점 완료 대기',AWAITING_REFLECTION:'정답 확인 · 도움 사용 여부 확인',NEEDS_PRACTICE:'이번 훈련은 재연습 필요',AC_WITH_HELP:'도움을 받아 정답 해결',SELF_REPORTED_UNASSISTED_AC:'도움 없이 정답 해결 · 본인 확인'};
export default function FollowupPanel({api,onOpen,onGeneration,locked}) {
  const [items,setItems]=useState([]),[busy,setBusy]=useState(false),[error,setError]=useState('');
  async function refresh(){setItems(await api('/api/practice-followups'));}
  const waiting=items.some(item=>['ACTIVE','WAITING_JUDGE'].includes(item.status)||['QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING','BUILD_QUEUED','BUILD_GENERATING','CHECKING','REVIEW_QUEUED','REVIEW_GENERATING','REVIEW_CHECKING','FINAL_QUEUED','FINAL_GENERATING','FINAL_CHECKING'].includes(item.generationStatus));
  useEffect(()=>{let stopped=false;
    async function load(){try{const value=await api('/api/practice-followups');if(!stopped)setItems(value);}catch(e){if(!stopped)setError(e.message);}}
    load();window.addEventListener('focus',load);window.addEventListener('gamjaoj-training-changed',load);window.addEventListener('gamjaoj-followup-created',load);
    const timer=waiting?setInterval(load,5000):null;
    return()=>{stopped=true;clearInterval(timer);window.removeEventListener('focus',load);window.removeEventListener('gamjaoj-training-changed',load);window.removeEventListener('gamjaoj-followup-created',load);};
  },[waiting]);
  async function action(item,phase,body){
    if(busy)return;setBusy(true);setError('');try{
      const value=await api(`/api/practice-followups/${item.id}/${phase}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...body,round:item.round||1})});
      await refresh();
      if(phase==='start')await onOpen(value.problemVersion);
      if(phase==='generate')onGeneration();
    }catch(e){setError(e.message);if(e.status===409){try{await refresh();}catch{ /* Keep the original recovery message. */ }}}finally{setBusy(false);}
  }
  return <section className="followup-panel" aria-label="다음 훈련">
    <h3>확인한 목표로 다음 훈련</h3>
    <p className="muted">풀이 피드백에서 연습할 지점을 확인하면 이곳에 이어서 기록합니다. 정답 한 번으로 숙련도를 확정하지 않아요.</p>
    {error&&<p role="alert" className="notice error">{error}</p>}
    {items.map((item,index)=><details key={item.id} open={index===0} className="followup-record"><summary>{item.round||1}차 · {labels[item.status]||item.status} · {item.goal.length>120?item.goal.slice(0,120)+'…':item.goal}</summary>
      <p>{item.goal}</p>
      {item.status==='READY_TO_PRACTICE'&&<>
        {item.candidates.length>0?<><p className="draft-help">같은 검증된 풀이 유형과 선택한 목표의 후보입니다. 문제 내용을 확인하고 시작해 주세요. 아직 정답 처리하지 않은 문제만 표시합니다.</p>
          {item.candidates.map(candidate=><div className="followup-candidate" key={candidate.version}><div>{candidate.title}<small>{candidate.version}</small>{candidate.statement&&<details><summary>문제 내용 확인</summary><p className="spec-text">{candidate.statement}</p></details>}</div><button className="secondary" disabled={locked||busy} onClick={()=>action(item,'start',{problemVersion:candidate.version})}>이 문제로 훈련</button></div>)}</>:<p>조건에 맞는 준비된 문제가 없어요. 태그만 비슷한 문제로 대신하지 않습니다.</p>}
        {!item.generationStatus&&<button className="secondary" disabled={busy||locked} onClick={()=>action(item,'generate')}>이 목표로 새 문제 요청</button>}
        {item.generationStatus&&<><p className="draft-help">출제 요청이 저장돼 있어요. 생성 화면에서 진행을 확인하고, 검증·게시를 마치면 이곳에서 선택할 수 있어요.</p><button className="secondary" onClick={onGeneration}>생성 진행 확인</button></>}
      </>}
      {item.status==='ACTIVE'&&<button className="secondary" disabled={locked||busy} onClick={async()=>{setError('');try{await onOpen(item.problemVersion);}catch(e){setError(e.message);}}}>이 훈련 이어 풀기</button>}
      {item.status==='AWAITING_REFLECTION'&&<><p>마지막 정식 제출이 정답입니다. 이번에는 힌트·해설·다른 사람의 도움 없이 해결했나요? 외부 도움은 자동으로 판별하지 않으며 본인 확인으로 기록합니다.</p>
        <div className="editor-tools"><button className="secondary" disabled={busy} onClick={()=>action(item,'reflect',{usedHelp:false})}>도움 없이 해결했어요</button><button className="secondary" disabled={busy} onClick={()=>action(item,'reflect',{usedHelp:true})}>도움을 받아 해결했어요</button></div></>}
      {item.status==='NEEDS_PRACTICE'&&<p className="draft-help">이번 시도는 정답으로 마치지 못했어요. 같은 목표로 새 훈련을 시작할 수 있습니다.</p>}
      {['NEEDS_PRACTICE','AC_WITH_HELP','SELF_REPORTED_UNASSISTED_AC'].includes(item.status)&&<button className="secondary" disabled={busy||locked} onClick={()=>action(item,'repeat')}>같은 목표로 다시 연습</button>}
      {item.attempts?.length>0&&<details><summary>이전 시도 ({item.attempts.length})</summary><ul>{item.attempts.map(attempt=><li key={attempt.round}><p>{attempt.round}차 · {labels[attempt.status]||attempt.status} · {attempt.problemVersion}</p><button className="secondary" onClick={()=>window.dispatchEvent(new CustomEvent('gamjaoj-training-open',{detail:attempt.sessionId}))}>{attempt.round}차 훈련 기록 보기</button></li>)}</ul></details>}
      {item.status==='HELD'&&<p className="notice">원래 문제 또는 훈련 문제가 검토 중입니다. 기존 기록은 보존하며 학습 근거로 사용하지 않습니다.</p>}
    </details>)}
  </section>;
}
