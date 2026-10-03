'use client';
import {useEffect,useState} from 'react';
import {thinkingLayers} from './thinking-difficulty';

export default function GrowthSummary({api,onTarget,onNavigate,activity=0}){
 const [growth,setGrowth]=useState(null),[error,setError]=useState(''),[refresh,setRefresh]=useState(0);
 useEffect(()=>{let live=true,request=0;
  async function load(event){const token=++request;try{const value=await api('/api/my/growth',{fresh:!!event});if(!Number.isInteger(value.layer)||!Array.isArray(value.evidence))throw new Error('성장 기록을 불러오지 못했어요.');if(live&&token===request){setGrowth(value);setError('');}}catch(e){if(live&&token===request)setError(e.message);}}
  load();window.addEventListener('gamjaoj-problems-changed',load);window.addEventListener('focus',load);return()=>{live=false;window.removeEventListener('gamjaoj-problems-changed',load);window.removeEventListener('focus',load);};
 },[api,activity,refresh]);
 return <section className="growth-summary" aria-label="나의 성장 겹">
  {!growth?<div className="growth-loading"><p role={error?'alert':'status'}>{error||'성장 기록을 불러오는 중…'}</p>{error&&<button className="secondary" onClick={()=>setRefresh(x=>x+1)}>성장 기록 다시 불러오기</button>}</div>:<>
   <div className="growth-identity"><span className="growth-mark" data-layer={growth.layer} aria-hidden="true">{growth.layer||'—'}<small>겹</small></span><div><span className="eyebrow">나의 성장 겹</span><h2>{growth.layer?`${growth.layer}겹 · ${thinkingLayers[growth.layer-1][0]}`:'첫 겹을 쌓는 중'}</h2><p>검토된 문제 {growth.eligibleProblems}개 해결 · {growth.categories}개 분야</p></div></div>
   <div className="growth-next">{growth.nextLayer?<><div className="growth-goal"><strong>다음 목표 · {growth.nextLayer}겹</strong><span>{growth.nextSolved} / {growth.required}문제</span></div><progress max={growth.required} value={growth.nextSolved} aria-label={`${growth.nextLayer}겹 달성 진행도`}/><p>{growth.nextLayer}겹 이상 문제를 {growth.required-growth.nextSolved}개 더 해결하면 다음 겹에 도달해요.</p>{onTarget&&<button className="growth-challenge" onClick={()=>onTarget(growth.nextLayer)}>다음 겹 문제 찾아보기 <span aria-hidden="true">→</span></button>}</>:<><strong>9겹까지 쌓았어요.</strong><p>새로운 분야와 풀이 방법으로 깊이를 넓혀보세요.</p>{onNavigate&&<button className="growth-challenge" onClick={()=>onNavigate('training')}>맞춤 훈련으로 이어가기 →</button>}</>}</div>
   <div className="growth-footer"><details><summary>성장 겹은 어떻게 정해지나요?</summary><p>같은 겹 이상의 검토된 문제를 서로 다르게 5개 해결하면 그 겹을 인정해요. 정식 제출의 AC만 반영하고, 같은 문제의 반복 제출은 한 번으로 셉니다. 진단·코드 실행·직접 지정한 출제자 난도·검토 보류 문제는 제외해요. 문제의 겹이 바뀌면 현재 기록으로 다시 계산합니다. 실력의 확정 등급이나 경쟁 순위가 아닌 성장 기록이에요.{growth.excludedProblems>0&&` 해결한 문제 중 ${growth.excludedProblems}개는 검토 난도가 없어 산정에서 제외됐어요.`}</p></details>{onNavigate&&<nav aria-label="성장 기록 바로가기"><button onClick={()=>onNavigate('mypage')}>내 풀이 기록</button><button onClick={()=>onNavigate('training')}>맞춤 훈련</button><button onClick={()=>onNavigate('diagnostic')}>선택 진단</button></nav>}</div>
   {error&&<p role="alert" className="growth-error">성장 기록 갱신 실패 · <button onClick={()=>setRefresh(x=>x+1)}>다시 시도</button></p>}
  </>}
 </section>;
}
