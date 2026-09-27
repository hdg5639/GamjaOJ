'use client';
import {useEffect,useState} from 'react';
import {categoryLabels} from './diagnostic-categories';
const tones={STRENGTH:'강점',WATCH:'주의',RISK:'위험'};
const outcomes={OPEN:'미완료',PASSED:'통과',EXHAUSTED:'5회 소진',SKIPPED:'건너뜀'};
const difficulty={EASY:'하',MEDIUM:'중'};
/** Rule request text the learner reviews and edits before submitting; never sent automatically. */
export function ruleDraft(category,observation) {
  const label=categoryLabels[category]||category;
  return `${label} 연습 문제 규칙. 진단에서 관찰된 코드 습관: ${observation.pattern} 이 습관이 문제가 되는 경우(${observation.risk})를 테스트로 구분할 수 있어야 한다.`.slice(0,1000);
}
export default function DiagnosticProfile({api,sessionId,row,onObservation,onRuleDraft}) {
  const [profile,setProfile]=useState(null),[error,setError]=useState('');
  useEffect(()=>{let stopped=false;setError('');
    api(`/api/diagnostics/${sessionId}/evaluations/${row.id}/profile`).then(v=>{if(!stopped)setProfile(v);}).catch(e=>{if(!stopped)setError(e.message);});
    return()=>{stopped=true;};
  },[sessionId,row.id,row.status,row.corrections?.length]);
  if(error)return <p role="alert" className="notice error">분야별 요약을 불러오지 못했어요. {error}</p>;
  if(!profile)return <p role="status">분야별 요약을 불러오고 있어요…</p>;
  const all=profile.categories.flatMap(c=>c.observations);
  const count=tone=>all.filter(o=>o.tone===tone).length;
  const ruleName=id=>profile.rules.find(r=>r.id===id)?.label||id;
  return <section className="diagnostic-profile" aria-label="분야별 진단 요약">
    <h3>분야별 한눈에 보기</h3>
    {all.some(o=>o.tone)?<p>코드 근거가 있는 관찰: 강점 {count('STRENGTH')} · 주의 {count('WATCH')} · 위험 {count('RISK')}{all.some(o=>o.repeated)?` · 다른 분야에서도 보인 습관 ${all.filter(o=>o.repeated).length}`:''}</p>:
      <p className="muted">{row.interpretation?'이 평가는 습관·위험 구분이 추가되기 전에 만들어졌어요. 새로 평가를 요청하면 분야별 습관이 표시됩니다.':'AI 해석이 준비되면 분야별 코드 습관과 위험이 표시됩니다. 아래는 판정 기록입니다.'}</p>}
    <p className="muted">습관은 이번 진단 제출 코드에서 보인 패턴이며 성향이나 실력 등급이 아닙니다. 선택하지 않았거나 건너뛴 분야는 약점이 아니라 미평가입니다.</p>
    <div className="diagnostic-profile-grid">{profile.categories.map(category=><article key={category.id} className="diagnostic-profile-card" data-selected={category.selected}>
      <h4>{categoryLabels[category.id]||category.id}</h4>
      {!category.selected?<p className="muted">선택하지 않음 · 미평가</p>:<ul className="diagnostic-profile-items">{category.items.map(item=><li key={item.itemId}>
        {difficulty[item.difficulty]||item.difficulty} · {item.externallySeen?'본 적 있음 · 근거 제외':outcomes[item.status]||item.status} · 제출 {item.attempts}회</li>)}</ul>}
      {category.observations.map(o=><div key={o.index} className="diagnostic-habit" data-tone={o.tone||'NONE'}>
        <p>{o.tone&&<span className="diagnostic-tone">{tones[o.tone]}</span>}{o.repeated&&<span className="diagnostic-tone" data-kind="repeated">다른 분야에서도 보임</span>}</p>
        <p><strong>{o.pattern||'코드 관찰'}</strong></p>
        {o.risk&&<p>{o.tone==='STRENGTH'?'유지할 이유':'위험해지는 경우'}: {o.risk}</p>}
        <button className="secondary" onClick={()=>onObservation(o.index)}>관찰 {o.index+1} 근거·학습 계획 보기</button>
        {category.selected&&o.tone&&o.tone!=='STRENGTH'&&category.ruleIds.length===0&&profile.ruleOnboardingEnabled&&onRuleDraft&&
          <button className="secondary" onClick={()=>onRuleDraft(ruleDraft(category.id,o))}>이 습관으로 규칙 등록 요청 작성</button>}
      </div>)}
      {category.alsoSeen.length>0&&<p className="muted">다른 분야 관찰 {category.alsoSeen.map(i=>i+1).join(', ')}번에서 같은 습관이 이 분야 제출에도 보였어요.</p>}
      {category.selected&&category.observations.length===0&&row.interpretation&&<p className="muted">이 분야에서 코드 근거로 짚은 습관은 없어요.</p>}
      {category.ruleIds.length>0&&<p className="muted">분야 이름이 맞는 등록 규칙: {category.ruleIds.map(ruleName).join(', ')}. 관찰에서 학습 목표를 저장하면 이 규칙으로 문제를 만들 수 있어요.</p>}
    </article>)}</div>
    {profile.ruleOnboardingEnabled&&<p className="muted">규칙 등록 요청 작성은 등록 화면에 문장만 채워 넣어요. 내용을 확인하고 직접 요청해야 하며, 요청하면 AI 예산을 사용합니다.</p>}
  </section>;
}
