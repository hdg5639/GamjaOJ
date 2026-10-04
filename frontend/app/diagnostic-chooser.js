'use client';
import {useState} from 'react';
import {categoryLabels,bankTitle} from './diagnostic-categories';

function description(bank){
 if(bank.examType==='A'||bank.id==='exam-a-v2')return {name:'A형 목표 진단',summary:'구현부터 상태 탐색까지, 복합 문제를 풀어내는 흐름을 확인해요.',format:'표준 입출력 · Java / C++ / Python',scope:'조건 구현·경계, 시뮬레이션, 조합·최적화, 상태 탐색',exam:true};
 if(bank.examType==='B'||bank.id==='exam-b-v2')return {name:'B형 목표 진단',summary:'여러 명령이 이어지는 상황에서 자료구조와 상태 관리 역량을 확인해요.',format:'함수 구현 · Java UserSolution',scope:'인덱스 구조, 우선순위·정렬, 동적 조회·구간 집계, 복합 설계·관계 경로',exam:true};
 if(bank.id.startsWith('algo-mix-'))return {name:bankTitle(bank.id),summary:'여러 알고리즘을 둘러보며 연습할 분야를 찾아요.',format:'표준 입출력 · Java / C++ / Python',scope:'배열·문자열부터 그래프·최소 신장 트리까지',exam:false};
 return {name:bankTitle(bank.id),summary:'구현·배열·자료구조·탐색의 기본 풀이를 확인해요.',format:'표준 입출력 · Java / C++ / Python',scope:'기초 구현, 배열·문자열, 자료구조, 탐색',exam:false};
}
export default function DiagnosticChooser({banks,scope,setScope,disabled,onStart}){
 const [selectedId,setSelectedId]=useState(null);
 const ordered=[...banks].sort((a,b)=>Number(description(b).exam)-Number(description(a).exam));
 const selected=ordered.find(b=>b.id===selectedId)||ordered[0];
 if(!selected)return <p className="muted">검토된 진단 문항을 준비하고 있어요. 일반 문제에서 먼저 연습할 수 있어요.</p>;
 const detail=description(selected),chosen=scope[selected.id]||selected.categories,count=detail.exam?selected.questionCount:chosen.length*2;
 return <div className="diagnostic-picker">
  <nav className="diagnostic-type-list" aria-label="진단 유형 선택">{ordered.map(bank=>{const d=description(bank);return <button type="button" className="secondary diagnostic-type" key={bank.id} aria-pressed={selected.id===bank.id} disabled={disabled} onClick={()=>setSelectedId(bank.id)}><strong>{d.name}</strong><span>{d.exam?`${bank.questionCount}문항 · ${bank.setCount||4}개 세트`:`${bank.categories.length}개 분야 · 분야 선택 가능`}</span></button>;})}<p className="muted diagnostic-picker-note">풀어본 문제를 바탕으로<br/>다음 훈련을 연결해요.</p></nav>
  <fieldset className="diagnostic-choice-detail" disabled={disabled} key={selected.id}><legend className="sr-only">{detail.name} 설정</legend>
   <header><span className="diagnostic-choice-kicker">{detail.exam?'목표별 역량 확인':'분야별 시작점 확인'}</span><h3>{detail.name}</h3><p>{detail.summary}</p></header>
   <dl className="diagnostic-choice-facts"><div><dt>진행 방식</dt><dd>{detail.format}</dd></div><div><dt>확인하는 역량</dt><dd>{detail.scope}</dd></div></dl>
   {detail.exam?<div className="diagnostic-exam-scope"><h4>4개 영역, 기본·응용 한 쌍씩</h4><ul>{selected.categories.map(c=><li key={c}><span>{categoryLabels[c]||c}</span><small>기본 + 응용</small></li>)}</ul><p className="muted">전체 {selected.questionCount}문항을 진행해요. 아직 배정받지 않은 세트를 우선 선택하고, 모두 배정받았다면 풀어본 세트로 재응시해요.</p></div>:<div className="diagnostic-scope-picker"><div className="diagnostic-scope-heading"><h4>진단할 분야</h4><button type="button" className="secondary" onClick={()=>setScope(previous=>({...previous,[selected.id]:chosen.length===selected.categories.length?[]:selected.categories}))}>{chosen.length===selected.categories.length?'전체 해제':'전체 선택'}</button></div><div className="diagnostic-category-options">{selected.categories.map(c=><label key={c}><input type="checkbox" checked={chosen.includes(c)} onChange={e=>setScope(previous=>({...previous,[selected.id]:e.target.checked?[...chosen,c]:chosen.filter(x=>x!==c)}))}/><span>{categoryLabels[c]||c}<small>하·중 2문항</small></span></label>)}</div></div>}
   <details className="diagnostic-entry-guide"><summary>진행 규칙과 결과 안내</summary><p>문항당 정식 제출은 최대 5회예요. 정답 또는 5회 소진 후 다음 문항으로 넘어가며, 코드 실행은 횟수 제한이 없어요. 건너뛰기·일시정지·초안 저장을 지원해요.</p><p>시범 진단으로 난도와 세트 간 동등성은 보정 전이에요. 공식 시험 합격 여부나 전체 숙련도를 판정하지 않으며, 풀이 기록과 본인 보고를 바탕으로 훈련을 제안해요.</p></details>
   <footer className="diagnostic-choice-start"><div><strong>{count}문항</strong><span>{detail.exam?'전체 세트 · 자동 배정':`${chosen.length}개 분야 선택`}</span></div><button className="primary" disabled={!count} onClick={()=>onStart(selected,detail.exam?null:chosen)}>{detail.exam?`${detail.name} 시작`:`선택한 ${count}문항 시작`}</button></footer>
  </fieldset>
 </div>;
}
