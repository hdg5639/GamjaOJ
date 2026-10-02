'use client';
import {useEffect,useRef,useState} from 'react';
import {categoryLabels} from './diagnostic-categories';
const catalogCategory={'implementation':'구현','arrays-strings':'배열·문자열','basic-data-structures':'기초 자료구조','basic-search':'기초 탐색','bfs':'너비 우선 탐색','dfs':'깊이 우선 탐색','backtracking':'백트래킹','dp':'동적 계획법','binary-search':'이분 탐색','greedy':'탐욕법','graph':'그래프·최단 경로','mst':'최소 신장 트리'};
export default function DiagnosticRoadmap({api,items,row,onObservation,onOpen,onAssess,sessionId}) {
 const [problems,setProblems]=useState([]),[error,setError]=useState('');
 const [busy,setBusy]=useState(false),[pending,setPending]=useState(null);const lock=useRef(false);
 const storageKey=`gamjaoj-diagnostic-basic-request-${sessionId}`;
 useEffect(()=>{try{const value=JSON.parse(sessionStorage.getItem(storageKey));if(value?.key&&value?.body?.problemVersion&&typeof value.body.goal==='string')setPending(value);}catch{}},[storageKey]);
 async function start(problem,category){if(lock.current)return;lock.current=true;setBusy(true);setError('');const attempt=pending||{key:crypto.randomUUID(),body:{problemVersion:problem.version,goal:`진단 후 기초 복습 · ${categoryLabels[category]||category} 접근 방법 확인하기`}};setPending(attempt);try{sessionStorage.setItem(storageKey,JSON.stringify(attempt));}catch{}
  try{await api('/api/training-sessions',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':attempt.key},body:JSON.stringify(attempt.body)});await onOpen(attempt.body.problemVersion);setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}}
  catch(e){setError(e.message);if(e.status>=400&&e.status<500){setPending(null);try{sessionStorage.removeItem(storageKey);}catch{}}}finally{lock.current=false;setBusy(false);}}
 const needsBasics=[...new Set(items.filter(i=>!i.externallySeen&&i.skipReason==='NOT_SURE').map(i=>i.category))];
 const observations=(row?.interpretation?.observations||[]).map((o,index)=>({...o,index})).filter(o=>o.tone!=='STRENGTH').sort((a,b)=>({RISK:0,WATCH:1}[a.tone]??2)-({RISK:0,WATCH:1}[b.tone]??2));
 useEffect(()=>{let live=true;if(needsBasics.length)api('/api/problems').then(data=>{if(live)setProblems(Array.isArray(data)?data:[]);}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};},[api,needsBasics.join(',')]);
 return <section className="diagnostic-roadmap" aria-label="추천 커리큘럼"><header><span className="report-section-number">02</span><div><h3>다음 연습의 순서</h3><p className="muted">이번 진단의 코드 관찰과 본인 보고를 바탕으로 제안해요. 필요한 단계부터 골라 진행하세요.</p></div></header>
  {error&&<p role="alert">연습 연결을 확인하지 못했어요. {error}</p>}
  {pending&&<button className="secondary" disabled={busy} onClick={()=>start()}>같은 기초 훈련 요청 다시 확인</button>}
  <ol className="diagnostic-roadmap-steps">
   {needsBasics.map(category=>{const candidates=problems.filter(p=>p.category===catalogCategory[category]&&p.difficulty==='EASY'&&p.submissionsEnabled&&!p.problemHeld).sort((a,b)=>Number(a.solveStatus==='SOLVED')-Number(b.solveStatus==='SOLVED')).slice(0,2);return <li key={category}><span className="roadmap-kind">기초 복습 · 본인 보고</span><h4>{categoryLabels[category]||category}의 접근 방법부터</h4><p>‘접근 방법을 모르겠어요’로 넘긴 문항이 있어요. 기본 개념을 확인하고 하 난도 문제에서 풀이 순서를 직접 설명해 보세요.</p>{candidates.map(p=><button className="secondary" key={p.version} disabled={busy||!!pending} onClick={()=>start(p,category)}>{p.title} · 기초 훈련 시작</button>)}{!candidates.length&&<p className="muted">지금 선택할 수 있는 같은 분야의 하 난도 문제가 없어요. 문제 탐색에서 직접 고를 수 있어요.</p>}<small>같은 분야의 연습 후보예요. 진단 문항과 같은 풀이 규칙인지 검증된 추천은 아닙니다.</small></li>;})}
   {observations.slice(0,4).map(o=><li key={o.index}><span className="roadmap-kind">{o.confidence==='SUPPORTED'&&o.nextAction==='PRACTICE'?'코드 근거 · 보완 연습':'추가 확인 · 가설'}</span><h4>{o.pattern||`관찰 ${o.index+1} 다시 확인하기`}</h4><p>{o.recommendation}</p><button className="secondary" onClick={()=>onObservation(o.index)}>근거 확인하고 목표 정하기</button></li>)}
   <li><span className="roadmap-kind">새 문제로 확인</span><h4>{needsBasics.length||observations.length?'연습한 내용을 다른 문제에서 확인하기':'아직 확인하지 못한 부분부터'}</h4><p>{needsBasics.length||observations.length?'힌트 없이 새 문제에 적용하고, 혼자 다시 풀 수 있는지 기록하세요. 같은 문제의 재통과만으로 숙련을 확정하지 않아요.':'건너뛰기 사유가 없거나 시간이 부족했던 문항은 실력을 판단할 근거가 부족해요. 원하는 분야를 다시 확인하세요.'}</p><button className="secondary" onClick={onAssess}>다른 진단 선택하기</button></li>
  </ol>
 </section>;
}
