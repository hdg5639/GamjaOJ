'use client';
import {thinkingLabel} from './thinking-difficulty';
import {useEffect,useRef,useState} from 'react';
import {confidenceLabels} from './problem-reflection';
import ListPagination from './list-pagination';

export default function LearningActivity({api,userId,activity,refresh,onChoose,growth,stats}){
 const [data,setData]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[selected,setSelected]=useState(null);
 const scroll=useRef(null);
 const [categoryScope,setCategoryScope]=useState('practiced'),[categoryPage,setCategoryPage]=useState(1);
 useEffect(()=>{setCategoryScope('practiced');setCategoryPage(1);},[userId]);
 useEffect(()=>{setData(null);setSelected(null);},[userId]);
 useEffect(()=>{let live=true;setBusy(true);setError('');api('/api/my/learning').then(result=>{if(live&&Array.isArray(result.days)){setData(result);setSelected(result.days.at(-1));}}).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setBusy(false);});return()=>{live=false;};},[api,userId,activity,refresh]);
 useEffect(()=>{if(scroll.current)scroll.current.scrollLeft=scroll.current.scrollWidth;},[data]);
 if(error||!data)return <div className="learning-activity my-learning-layout" aria-busy={busy}>{growth}<div className="learning-overview-status">{error?<p role="alert" className="notice error">학습 기록을 불러오지 못했어요: {error}</p>:<p className="muted" role="status">{busy?'학습 기록을 불러오는 중…':'학습 기록이 아직 없어요.'}</p>}{stats}</div></div>;
 const pad=(new Date(data.start+'T00:00:00Z').getUTCDay()+6)%7;
 const cells=[...Array(pad).fill(null),...data.days];
 const months=data.days.flatMap((day,i)=>i===0||day.date.endsWith('-01')?[{label:`${Number(day.date.slice(5,7))}월`,column:Math.floor((i+pad)/7)+1}]:[]);
 const max=Math.max(1,...data.categories.map(c=>c.attempted));
 const practiced=data.categories.filter(c=>c.attempted>0).sort((a,b)=>b.attempted-a.attempted||a.category.localeCompare(b.category,'ko'));
 const untouched=data.categories.filter(c=>c.attempted===0).sort((a,b)=>a.category.localeCompare(b.category,'ko'));
 const categories=categoryScope==='practiced'?practiced:untouched;
 const categoryPages=Math.max(1,Math.ceil(categories.length/6)),currentCategoryPage=Math.min(categoryPage,categoryPages);
 return <div className="learning-activity my-learning-layout" aria-busy={busy}>
  {growth}
  <section className="activity-calendar" aria-label="풀이 잔디">
   <div className="learning-section-heading"><h3>풀이 잔디</h3><p className="muted">최근 1년 · 한국 시간</p></div>
   <p className="activity-summary">활동한 날 <strong>{data.activeDays}일</strong><span>현재 연속 <strong>{data.currentStreak}일</strong></span><span>최장 연속 <strong>{data.longestStreak}일</strong></span></p>
   <div className="activity-calendar-scroll" ref={scroll} tabIndex={0} aria-label="최근 1년 풀이 잔디, 가로로 이동할 수 있어요"><div className="activity-calendar-board"><div className="activity-months" style={{'--weeks':Math.ceil(cells.length/7)}} aria-hidden="true">{months.map((month,i)=><span key={i} style={{gridColumn:month.column}}>{month.label}</span>)}</div><div className="activity-calendar-grid">
    {cells.map((day,i)=>day?<button type="button" tabIndex={selected?.date===day.date?0:-1} key={day.date} className={`activity-day level-${Math.min(4,day.solved)}`} aria-label={`${day.date} 정답 ${day.solved}문제`} aria-pressed={selected?.date===day.date} title={`${day.date} · ${day.solved}문제`} onFocus={()=>setSelected(day)} onClick={()=>setSelected(day)} onKeyDown={event=>{const offset={ArrowRight:7,ArrowLeft:-7,ArrowDown:1,ArrowUp:-1}[event.key];if(offset){event.preventDefault();event.currentTarget.parentElement.children[i+offset]?.focus();}}}/>:<span key={`pad-${i}`} aria-hidden="true"/>)}
   </div></div></div>
   <div className="activity-calendar-footer"><p role="status">{selected?`${selected.date} · 정답 ${selected.solved}문제`:'날짜를 선택하면 풀이 수를 볼 수 있어요.'}</p><span className="activity-legend" aria-label="잔디 진하기: 0, 1, 2, 3, 4문제 이상">적음 {[0,1,2,3,4].map(n=><i key={n} className={`activity-day level-${n}`} aria-hidden="true"/>)} 많음</span></div>
   <p className="draft-help">하루에 정답을 맞힌 서로 다른 일반 문제 수예요. 같은 문제의 반복 제출, 직접 실행, 진단은 더하지 않아요.</p>
  </section>
  <section className="learning-balance" aria-label="유형 균형">
   <div className="learning-section-heading"><h3>유형도 골고루</h3><p className="muted">최근 90일 · 서로 다른 도전 문제 {data.practicedProblems}개</p></div>
   <p>{data.dominantCategory?`${data.dominantCategory}에 도전이 많이 모였어요. 다른 유형도 한 문제씩 섞어 봐요.`:data.practicedProblems?'최근 도전한 분야를 보고, 덜 풀어본 유형을 골랐어요.':'익숙한 분야부터 시작하고, 다른 유형도 조금씩 섞어 봐요.'}</p>
   <div className="category-overview">
    <nav className="category-scopes" aria-label="유형 분포 범위">{[['practiced','도전한 유형',practiced.length],['untouched','아직 안 푼 유형',untouched.length]].map(([key,label,count])=><button type="button" key={key} aria-pressed={categoryScope===key} onClick={()=>{setCategoryScope(key);setCategoryPage(1);}}>{label} <span>{count}</span></button>)}</nav>
    <p className="category-range muted" role="status">{categories.length?`${categories.length}개 유형 · ${(currentCategoryPage-1)*6+1}–${Math.min(currentCategoryPage*6,categories.length)}번째`:'최근 90일 기준'}</p>
    <div className="category-distribution" aria-label="분야별 도전 분포">{categories.slice((currentCategoryPage-1)*6,currentCategoryPage*6).map(c=><div key={c.category}><span>{c.category}</span><div className="category-bar" aria-hidden="true"><i style={{width:`${c.attempted/max*100}%`}}/></div><small>{categoryScope==='practiced'?`도전 ${c.attempted} · 정답 ${c.solved}`:`문제 ${c.available??0}개`}</small></div>)}</div>
    {!categories.length&&<p className="muted">{categoryScope==='practiced'?'최근 90일 동안 도전한 유형이 없어요. 아직 안 푼 유형에서 시작해 보세요.':'모든 유형에 도전했어요.'}</p>}
    <ListPagination page={currentCategoryPage} pages={categoryPages} onChange={setCategoryPage} label="유형 분포 페이지"/>
   </div>
  </section>
  <section className="learning-continuation" aria-label="다음 학습">
   {stats}
   <div className="learning-next"><h3>다른 유형도 풀어보기</h3>{data.explore.length?<ul>{data.explore.map(p=><li key={p.version}><div><strong>{p.title}</strong><small>{p.category} · {thinkingLabel(p)}</small><p>{p.reason}</p></div><button className="secondary" onClick={()=>onChoose(p.version)} aria-label={`${p.title} 풀기`}>풀어보기</button></li>)}</ul>:<p className="muted">지금 선택할 수 있는 새로운 문제가 없어요.</p>}</div>
  {!!data.revisit.length&&<section className="learning-revisit" aria-label="다시 풀 문제"><h3>다시 풀어볼까요?</h3><ul>{data.revisit.map(p=><li key={p.version}><div><strong>{p.title}</strong><small>{p.category} · {confidenceLabels[p.confidence]}</small></div><button className="secondary" onClick={()=>onChoose(p.version)} aria-label={`${p.title} 다시 풀기`}>다시 풀기</button></li>)}</ul></section>}
  {!!data.efficiencyRetry?.length&&<section className="learning-revisit" aria-label="효율 재도전"><h3>효율 재도전</h3><p className="muted">동일 조건의 본인 AC 기록을 비교한 별도 신호예요.</p><ul>{data.efficiencyRetry.map(p=><li key={`${p.version}-${p.metric}`}><div><strong>{p.title}</strong><p>{p.reason}</p></div><button className="secondary" onClick={()=>onChoose(p.version)}>효율 다시 보기</button></li>)}</ul></section>}
  <PerformanceRecords api={api} userId={userId} activity={activity} refresh={refresh}/>
  </section>
 </div>;
}

function PerformanceRecords({api,userId,activity,refresh}){
 const [page,setPage]=useState(0),[data,setData]=useState(null),[error,setError]=useState('');
 useEffect(()=>{setPage(0);},[userId]);
 useEffect(()=>{let live=true;setData(null);setError('');api(`/api/my/performance?page=${page}`).then(r=>{if(live)setData(r);}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};},[api,userId,activity,refresh,page]);
 const states={COMPARABLE:'동일 조건 비교 가능',MISSING_METRICS:'지표 미측정',UNKNOWN_PROFILE:'실행 조건 확인 불가',SHARED_EXECUTION:'공유 실행 · 비교 제외'};
 return <section className="learning-revisit" aria-label="AC 성능 이력"><h3>AC 성능 이력</h3><p className="muted">내부 Judge 기록 · 테스트별 최댓값. 시간에는 런타임 시작 비용이 포함되고, cgroup 메모리는 언어 런타임 등도 포함해요. 실력 점수가 아니에요. 반복 표본이 부족하거나 측정 편차가 크면 추천을 만들지 않아요.</p>{error?<p role="alert">{error}</p>:!data?<p role="status">불러오는 중…</p>:<><ul>{data.items?.map(e=><li key={e.submissionId}><div><strong>{e.title} · {e.language}</strong><small>{e.submittedAt} · {e.maxWallMs==null?'시간 미측정':`${e.maxWallMs} ms`} · {e.maxMemoryBytes==null?'메모리 미측정':`${(e.maxMemoryBytes/1048576).toFixed(2)} MiB`}</small><p>{states[e.eligibility]}</p><details><summary>실행 profile</summary><pre>{JSON.stringify(e.comparisonProfile??e.executionProfile,null,2)??'기록 없음'}</pre><small>비교 조건 ID: {e.comparisonKey??'없음'}</small></details></div></li>)}</ul>{!data.items?.length&&<p>정식 AC 기록이 없어요.</p>}<button className="secondary" disabled={!page} onClick={()=>setPage(p=>p-1)}>이전</button> <button className="secondary" disabled={!data.hasMore} onClick={()=>setPage(p=>p+1)}>다음</button></>}</section>;
}
