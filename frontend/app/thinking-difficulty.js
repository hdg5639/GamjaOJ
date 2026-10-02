'use client';

export const thinkingLayers=[
 ['그대로','주어진 절차를 읽고 그대로 옮겨요.'],
 ['살펴보기','패턴과 예외를 살펴 필요한 정보를 찾아요.'],
 ['골라쓰기','맞는 도구나 풀이 방법을 골라 적용해요.'],
 ['이어붙이기','여러 조건이나 부분 결과를 연결해요.'],
 ['뒤집어보기','질문의 방향이나 관점을 바꿔 접근해요.'],
 ['상태 만들기','앞으로의 선택에 필요한 상황을 따로 기억해요.'],
 ['덜어내기','불필요한 상태·탐색·반복을 줄여요.'],
 ['꿰뚫기','겉으로 보이지 않는 구조를 찾아 증명해요.'],
 ['새로 짜기','여러 통찰을 모아 풀이 구조를 새로 설계해요.'],
];
export const thinkingLabel=p=>p?.thinking?`${p.thinking.layer}겹 · ${thinkingLayers[p.thinking.layer-1]?.[0]||p.thinking.name}`:'겹 미배정';
export const thinkingSource=p=>p?.thinking?.source==='CURATED_ESTIMATE'?'검토 추정':'출제자 추정';

export default function ThinkingDifficulty({problem,compact=false}){
 const t=problem?.thinking;
 if(compact)return <span className="thinking-inline">{thinkingLabel(problem)}</span>;
 return <details className="thinking-detail"><summary>{thinkingLabel(problem)}{t&&<small> · {thinkingSource(problem)}</small>}</summary>
  {t?<><dl className="thinking-profile">{[['발상','insight'],['구현','implementation'],['경계','edgeCases']].map(([label,key])=><div key={key}><dt>{label}</dt><dd><span className="thinking-scale" aria-hidden="true">{Array.from({length:5},(_,i)=><i key={i} data-filled={i<t[key]}/>)}</span><span>{t[key]} / 5</span></dd></div>)}</dl><p>{t.rationale}</p><p className="muted">문제의 공통 예상 난도예요. 내 자신감·풀이 평가는 돌아보기에서 따로 기록해요.</p></>:<p>아직 풀이 구조를 검토해 겹과 프로필을 배정하지 않았어요. 기존 하·중·상 설정을 자동 환산하지 않습니다.</p>}
 </details>;
}

export function ThinkingGuide(){return <details className="catalog-rating-note thinking-guide"><summary>생각의 겹 · 난도 기준 보기</summary>
 <p>겹은 이 문제의 풀이를 처음 설계할 때 필요한 생각의 깊이예요. 같은 알고리즘도 규칙·제약·풀이 구조에 따라 겹이 달라져요. 세 축을 평균 내거나 이름에 나온 기법만으로 결정하지 않아요.</p>
 <ol>{thinkingLayers.map(([name,description],i)=><li key={name}><strong>{i+1}겹 · {name}</strong><span>{description}</span></li>)}</ol>
 <dl><div><dt>발상</dt><dd>풀이 방법을 발견하고 타당성을 설명하는 부담</dd></div><div><dt>구현</dt><dd>코드 구조·자료 관리·여러 규칙을 맞추는 부담</dd></div><div><dt>경계</dt><dd>동률·빈 상태·수 범위·특수 상황을 챙기는 부담</dd></div></dl>
 <p>각 축은 1(적음)~5(매우 큼)입니다. 검토 추정은 최종 풀이·제약을 검토한 배정, 출제자 추정은 작성자의 배정이에요. 풀이 통계로 보정한 확정 등급은 아니며 외부 사이트 등급과 대응하지 않아요. 미배정은 쉬운 문제가 아니라 아직 판단하지 않은 문제예요. 해결 상태는 내 전체 정식 제출의 AC 기준입니다.</p>
 </details>;}
