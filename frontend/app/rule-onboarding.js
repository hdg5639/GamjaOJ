'use client';

import {useEffect,useRef,useState} from 'react';
import Pager,{usePage} from './pager';
import {categoryLabels} from './diagnostic-categories';

const labels={QUEUED:'대기',AUTHORING:'규칙·코드 작성 중',AUTHORED:'독립 검증 코드 준비',ORACLE:'독립 검증 코드 작성 중',QUALIFYING:'실행 검증 중',ACTIVE:'등록 완료',HELD:'검증 보류',FAILED:'등록 실패',CANCELLED:'취소됨',DEADLINE_EXCEEDED:'처리 기한 초과'};
const running=['QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING'];
const difficulties={EASY:'하',MEDIUM:'중',HARD:'상',EXPERT:'최상'};
const styles={GENERAL:'일반',SIMULATION:'시뮬레이션 · SW 역량테스트형',COMMAND:'명령 처리 · B형'};
const styleHelp={GENERAL:'새로운 규칙의 세계에서 어떤 기법을 써야 할지 스스로 판단하는 문제예요.',SIMULATION:'격자나 세계가 여러 규칙에 따라 단계별로 변하고, 과정 뒤의 결과를 구하는 문제예요.',COMMAND:'추가·변경·질의 명령이 수만 번 섞여 들어오고, 질의마다 답을 출력하는 문제예요.'};
const followups={QUEUED:'문제 생성 대기',DESIGNING:'문제 작성 중',BUILDING:'문제 작성 중',VALIDATING:'문제 검증 중',REVIEWING:'최종 검토 중',PUBLISHED:'문제 게시 완료',FAILED:'문제 생성 실패',HELD:'문제 생성 보류',DEADLINE_EXCEEDED:'문제 생성 기한 초과',CANCELLED:'문제 생성 취소'};
const reasons={REFERENCE_ORACLE_DISAGREEMENT:'정답 코드와 독립 검증 코드의 결과가 달랐어요.',MUTANT_SURVIVED:'일부러 틀리게 만든 코드를 작은 입력으로 걸러내지 못했어요.',DUPLICATE_RULE_CONTRACT:'이미 등록된 규칙과 같아요.',ONBOARDING_BUDGET_CAP:'이 요청의 예산 한도를 넘었어요.',MONTHLY_BUDGET_EXHAUSTED:'이번 달 AI 예산이 부족해요.',STRESS_RESOURCE_MARGIN:'최대 입력에서 실행 시간 기준을 넘었어요.',LARGE_TESTS_NOT_DISCRIMINATING:'대형 입력이 느린 풀이와 효율적인 풀이를 구분하지 못했어요.',SLOW_SOLUTION_INCORRECT:'비교용 느린 풀이가 작은 입력에서 틀렸어요.',DOMAIN_VALIDATOR_REJECTED:'작성된 입력 일부가 입력 조건 검사를 통과하지 못했어요.',ONBOARDING_DEADLINE_EXCEEDED:'처리 기한 안에 마치지 못했어요.',INTERRUPTED_BY_RESTART:'서비스 재시작으로 작성이 중단됐어요. 같은 조건으로 다시 요청해 주세요.'};

export default function RuleOnboarding({api,onRegistered,draft,onOpen}) {
  const [enabled,setEnabled]=useState(null),[items,setItems]=useState([]),[rules,setRules]=useState([]);
  const [text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const [difficulty,setDifficulty]=useState('MEDIUM'),[style,setStyle]=useState('GENERAL'),[category,setCategory]=useState('AUTO');
  const [publish,setPublish]=useState(true),[shared,setShared]=useState(false),[target,setTarget]=useState(null);
  const pending=useRef(null),live=useRef(false),known=useRef(new Set()),form=useRef(null);
  // A diagnosis draft only fills the box; the learner reviews it and submits explicitly.
  useEffect(()=>{if(!draft||pending.current)return;
    if(draft.evaluationId){setTarget(draft);setText('');if(draft.category&&categoryLabels[draft.category])setCategory(draft.category);}else setText(draft.text||'');
    form.current?.scrollIntoView({block:'center'});form.current?.querySelector('select,textarea')?.focus();},[draft?.key]);
  async function refresh(){
    try{
      const [list,mine]=await Promise.all([api('/api/rules/onboarding'),api('/api/rules/mine')]);
      if(!live.current)return;setItems(list);setRules(mine);
      const active=list.filter(i=>i.status==='ACTIVE').map(i=>i.id);
      if(active.some(id=>!known.current.has(id))&&known.current.size>0)onRegistered?.();
      known.current=new Set(['seen',...active]);
    }catch(e){if(live.current)setError(e.message);}
  }
  useEffect(()=>{live.current=true;
    api('/api/rules/onboarding/options').then(v=>{if(live.current)setEnabled(!!v.enabled);}).catch(()=>{if(live.current)setEnabled(false);});
    refresh();return()=>{live.current=false;};},[]);
  const busyWork=items.some(i=>running.includes(i.status));
  const followupWork=items.some(i=>i.status==='ACTIVE'&&i.publish&&!i.followupError&&!['PUBLISHED','FAILED','CANCELLED','DEADLINE_EXCEEDED'].includes(i.followupStatus||''));
  const itemPaging=usePage(items,5),rulePaging=usePage(rules,5);
  useEffect(()=>{if(!busyWork&&!followupWork)return;const timer=setInterval(refresh,5000);return()=>clearInterval(timer);},[busyWork,followupWork]);
  async function submit(event){
    event.preventDefault();if(busy)return;
    pending.current ||= {key:crypto.randomUUID(),body:{request:text.trim(),difficulty,style,category,publish,shared,
      ...(target?{evaluationId:target.evaluationId,observationIndex:target.observationIndex}:{})}};
    setBusy(true);setError('');
    try{await api('/api/rules/onboarding',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':pending.current.key},body:JSON.stringify(pending.current.body)});
      pending.current=null;setText('');setTarget(null);await refresh();}
    catch(e){if(e.status>=400&&e.status<500)pending.current=null;setError(e.message);}
    finally{setBusy(false);}
  }
  const [removing,setRemoving]=useState(null),[removed,setRemoved]=useState('');
  async function removeRule(rule){
    if(busy)return;setBusy(true);setError('');setRemoved('');
    try{const result=await api(`/api/rules/${encodeURIComponent(rule.id)}`,{method:'DELETE'});setRemoving(null);
      setRemoved(result.outcome==='ARCHIVED'?`${rule.label} 규칙을 삭제했어요. 다른 회원이 이 규칙으로 만든 문제가 있어 규칙 기록은 사용 중지 상태로 보관돼요.`:`${rule.label} 규칙을 삭제했어요.`);
      await refresh();onRegistered?.();}
    catch(e){setError(e.message);}finally{setBusy(false);}
  }
  async function act(path,options){
    if(busy)return;setBusy(true);setError('');
    try{await api(path,options);await refresh();onRegistered?.();}catch(e){setError(e.message);}finally{setBusy(false);}
  }
  return <section className="rule-onboarding" aria-labelledby="rule-onboarding-heading">
    <h3 id="rule-onboarding-heading">새 문제 만들기</h3>
    <p className="draft-help">난이도와 스타일을 고르면 AI가 새로운 규칙의 문제를 설계하고, 정답 코드·별도로 작성한 완전탐색 검증 코드·오답·느린 풀이·대형 입력을 모두 실제 채점기로 검증한 뒤에만 등록합니다. 어떤 알고리즘을 써야 하는지는 본문에 드러나지 않아요. 요청당 AI 예산은 최대 $1, 처리 기한은 20분이며 실패해도 자동으로 다시 시도하지 않습니다.</p>
    {enabled===false&&<p className="notice">지금은 새 규칙을 등록할 수 없어요.</p>}
    {error&&<p className="notice error" role="alert">{error}</p>}
    {draft&&text===draft.text&&<p className="notice" role="status">진단 결과에서 가져온 초안이에요. 내용을 확인하고 필요하면 고친 뒤 요청해 주세요.</p>}
    <form onSubmit={submit} ref={form}>
      {target&&<div className="notice" role="status"><p><strong>진단 습관 겨냥</strong> · {target.pattern}</p><p className="draft-help">이 습관대로 짠 코드가 실제로 틀리는 문제만 등록돼요. 오답 코드 하나를 이 습관으로 작성해 검증합니다.</p>
        <button type="button" className="secondary" disabled={busy||!!pending.current} onClick={()=>setTarget(null)}>겨냥 해제</button></div>}
      <div className="problem-request-options">
        <label className="field">난이도<select aria-label="문제 난이도" value={difficulty} disabled={busy||!!pending.current} onChange={e=>setDifficulty(e.target.value)}>{Object.entries(difficulties).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label>
        <label className="field">스타일<select aria-label="문제 스타일" value={style} disabled={busy||!!pending.current} onChange={e=>setStyle(e.target.value)}>{Object.entries(styles).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label>
        <label className="field">분야<select aria-label="문제 분야" value={category} disabled={busy||!!pending.current} onChange={e=>setCategory(e.target.value)}><option value="AUTO">자동으로 고르기</option>{Object.entries(categoryLabels).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label>
      </div>
      <p className="draft-help">{styleHelp[style]}</p>
      <label className="field">원하는 내용 (선택)<textarea rows={3} maxLength={1000} value={text} disabled={busy||!enabled||!!pending.current} onChange={e=>setText(e.target.value)} placeholder="예: 물류 창고 로봇, 회전하는 격자, 시간에 따라 열리는 문 같은 소재나 원하는 조건"/></label>
      <label className="check-row"><input type="checkbox" checked={publish} disabled={busy||!!pending.current} onChange={e=>setPublish(e.target.checked)}/>등록되면 바로 문제로 만들기</label>
      {publish&&<label className="check-row"><input type="checkbox" checked={shared} disabled={busy||!!pending.current} onChange={e=>setShared(e.target.checked)}/>만든 문제를 다른 회원에게도 공개</label>}
      <button className="secondary" disabled={busy||!enabled||busyWork||(!pending.current&&!target&&category==='AUTO'&&text.trim().length<10)}>{pending.current?'같은 요청 다시 확인':'이 조건으로 문제 만들기'}</button>
      {!target&&category==='AUTO'&&text.trim().length<10&&<p className="draft-help">분야를 고르거나 원하는 내용을 10자 이상 적어 주세요.</p>}
      {busyWork&&<p className="draft-help" role="status">진행 중인 등록이 끝나면 새로 요청할 수 있어요.</p>}
    </form>
    {items.length>0&&<ul className="rule-onboarding-list">{itemPaging.visible.map(item=><li key={item.id}>
      <p><strong>{labels[item.status]||item.status}</strong> · {item.label||item.request.slice(0,60)||'자동 주제'}{item.difficulty?` · ${difficulties[item.difficulty]||item.difficulty}`:''}{item.style&&item.style!=='GENERAL'?` · ${styles[item.style]}`:''}{item.targeted?' · 진단 습관 겨냥':''}{item.repairs>0?` · 자동 수정 ${item.repairs}회`:''}</p>
      {item.status==='ACTIVE'&&item.publish&&<p className="draft-help">{item.followupError?`문제를 바로 만들지 못했어요: ${item.followupError}`:item.followupStatus?followups[item.followupStatus]||item.followupStatus:'문제 생성 준비 중'}
        {item.publishedVersion&&onOpen&&<> <button className="primary" disabled={busy} onClick={()=>onOpen(item.publishedVersion)}>문제 풀기</button></>}</p>}
      {item.status==='QUALIFYING'&&<p className="draft-help">실행 검증 {Object.values(item.checks||{}).filter(v=>['AC','OK','WA'].includes(v)).length}건 완료</p>}
      {['HELD','FAILED','DEADLINE_EXCEEDED'].includes(item.status)&&<p className="draft-help">{reasons[item.error]||'검증 조건을 충족하지 못해 등록하지 않았어요.'}{item.failedCheck?` (실패한 검사: ${item.failedCheck})`:''} 사용한 AI 비용: ${Number(item.spentUsd||0).toFixed(3)}</p>}
      {running.includes(item.status)&&<button className="secondary" disabled={busy} onClick={()=>act(`/api/rules/onboarding/${item.id}/cancel`,{method:'POST'})}>이 등록 취소</button>}
    </li>)}</ul>}
    <Pager paging={itemPaging} label="규칙 등록 요청 페이지"/>
    {removed&&<p className="notice success" role="status">{removed}</p>}
    {rules.length>0&&<><h4>내가 등록한 규칙</h4><ul className="rule-onboarding-list">{rulePaging.visible.map(rule=><li key={rule.id}>
      <p><strong>{rule.label}</strong> · {rule.category} · {rule.status==='ACTIVE'?(rule.shared?'다른 회원에게 공개':'나만 사용'):'사용 중지'}</p>
      {rule.status==='ACTIVE'&&<button className="secondary" disabled={busy} onClick={()=>act(`/api/rules/${rule.id}/sharing`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({shared:!rule.shared})})}>{rule.shared?'공개 해제':'다른 회원에게 공개'}</button>}
      <button className="secondary" disabled={busy} onClick={()=>{setRemoving(rule.id);setRemoved('');}}>삭제</button>
      {removing===rule.id&&<div className="notice" role="group" aria-label={`${rule.label} 삭제 확인`}><p>이 규칙과 등록 기록을 삭제할까요? 이미 만든 문제는 그대로 남고, 이 규칙으로 새 문제를 만들 수 없게 돼요. 되돌릴 수 없어요.</p>
        <button className="danger" disabled={busy} onClick={()=>removeRule(rule)}>삭제하기</button> <button className="secondary" disabled={busy} onClick={()=>setRemoving(null)}>취소</button></div>}
    </li>)}</ul><Pager paging={rulePaging} label="내 규칙 페이지"/></>}
  </section>;
}
