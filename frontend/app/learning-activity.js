'use client';
import {useEffect,useRef,useState} from 'react';
import {confidenceLabels} from './problem-reflection';

export default function LearningActivity({api,userId,activity,refresh,onChoose}){
 const [data,setData]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[selected,setSelected]=useState(null);
 const scroll=useRef(null);
 useEffect(()=>{setData(null);setSelected(null);},[userId]);
 useEffect(()=>{let live=true;setBusy(true);setError('');api('/api/my/learning').then(result=>{if(live&&Array.isArray(result.days)){setData(result);setSelected(result.days.at(-1));}}).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setBusy(false);});return()=>{live=false;};},[api,userId,activity,refresh]);
 useEffect(()=>{if(scroll.current)scroll.current.scrollLeft=scroll.current.scrollWidth;},[data]);
 if(error)return <section className="learning-activity"><p role="alert" className="notice error">학습 기록을 불러오지 못했어요: {error}</p></section>;
 if(!data)return <p className="muted" role="status">{busy?'학습 기록을 불러오는 중…':'학습 기록이 아직 없어요.'}</p>;
 const pad=(new Date(data.start+'T00:00:00Z').getUTCDay()+6)%7;
 const cells=[...Array(pad).fill(null),...data.days];
 const months=data.days.flatMap((day,i)=>i===0||day.date.endsWith('-01')?[{label:`${Number(day.date.slice(5,7))}월`,column:Math.floor((i+pad)/7)+1}]:[]);
 const max=Math.max(1,...data.categories.map(c=>c.attempted));
 return <div className="learning-activity" aria-busy={busy}>
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
   <div className="learning-balance-layout"><div className="category-distribution" aria-label="분야별 도전 분포">{data.categories.map(c=><div key={c.category}><span>{c.category}</span><div className="category-bar" aria-hidden="true"><i style={{width:`${c.attempted/max*100}%`}}/></div><small>도전 {c.attempted} · 정답 {c.solved}</small></div>)}</div>
    <div className="learning-next"><h4>다른 유형도 풀어보기</h4>{data.explore.length?<ul>{data.explore.map(p=><li key={p.version}><div><strong>{p.title}</strong><small>{p.category} · {p.difficulty==='EASY'?'하':p.difficulty==='MEDIUM'?'중':p.difficulty==='HARD'?'상':'미분류'}</small><p>{p.reason}</p></div><button className="secondary" onClick={()=>onChoose(p.version)} aria-label={`${p.title} 풀기`}>풀어보기</button></li>)}</ul>:<p className="muted">지금 선택할 수 있는 새로운 문제가 없어요.</p>}</div>
   </div>
  </section>
  {!!data.revisit.length&&<section className="learning-revisit" aria-label="다시 풀 문제"><h3>다시 풀어볼까요?</h3><ul>{data.revisit.map(p=><li key={p.version}><div><strong>{p.title}</strong><small>{p.category} · {confidenceLabels[p.confidence]}</small></div><button className="secondary" onClick={()=>onChoose(p.version)} aria-label={`${p.title} 다시 풀기`}>다시 풀기</button></li>)}</ul></section>}
 </div>;
}
