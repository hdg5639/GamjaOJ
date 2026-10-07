'use client';

import {useEffect,useState} from 'react';

const slides=[
 {title:'내 방식으로 풀고',label:'문제 풀이',subtitle:'코드를 쓰고, 실행 결과를 확인해요.'},
 {title:'한 번 더 돌아보고',label:'풀이 복습',subtitle:'정답 뒤에 남은 질문도 기록해요.'},
 {title:'다음 연습으로 이어가요',label:'다음 연습',subtitle:'놓쳤던 조건을 다음 문제에서 다시 만나요.'},
];

export default function AuthLearningPreview({visible=true}){
 const [index,setIndex]=useState(0),[paused,setPaused]=useState(false),[hovered,setHovered]=useState(false),[eligible,setEligible]=useState(false);
 useEffect(()=>{
  const desktop=matchMedia('(min-width:1280px)'),motion=matchMedia('(prefers-reduced-motion:reduce)');
  const update=()=>{setEligible(desktop.matches&&!document.hidden);if(motion.matches)setPaused(true);};
  const focus=event=>{if(event.target.matches('input,textarea,select'))setPaused(true);};
  update();desktop.addEventListener('change',update);motion.addEventListener('change',update);document.addEventListener('visibilitychange',update);document.addEventListener('focusin',focus);
  return()=>{desktop.removeEventListener('change',update);motion.removeEventListener('change',update);document.removeEventListener('visibilitychange',update);document.removeEventListener('focusin',focus);};
 },[]);
 useEffect(()=>{
  if(!visible||!eligible||paused||hovered)return;
  const timer=setInterval(()=>setIndex(value=>(value+1)%slides.length),7000);
  return()=>clearInterval(timer);
 },[visible,eligible,paused,hovered,index]);
 function choose(next){setIndex(next);setPaused(true);}
 return <section className="auth-practice-preview auth-slideshow" aria-label="GamjaOJ 연습 흐름 예시" aria-roledescription="슬라이드쇼" onMouseEnter={()=>setHovered(true)} onMouseLeave={()=>setHovered(false)} onFocusCapture={()=>setPaused(true)}>
  <div className="auth-preview-heading"><span>이렇게 연습해요</span><span>예시 · {index+1} / {slides.length}</span></div>
  <div className="auth-preview-slides" aria-live={paused?'polite':'off'}>
   {slides.map((slide,i)=><div key={slide.label} className="auth-preview-slide" data-active={index===i} aria-hidden={index!==i} inert={index!==i} role="group" aria-roledescription="슬라이드" aria-label={`${i+1} / ${slides.length} · ${slide.label}`}>
    <div className="auth-preview-slide-title"><span>0{i+1} · {slide.label}</span><h2>{slide.title}</h2><p>{slide.subtitle}</p></div>
    {i===0&&<>
     <div className="auth-preview-problem"><span>배열 · Python</span><h3>가장 큰 값 찾기</h3><dl><div><dt>입력</dt><dd>−8, −3, −12</dd></div><div><dt>기대 결과</dt><dd>−3</dd></div></dl></div>
     <pre aria-label="Python 풀이 예시"><code><span className="auth-code-comment"># 첫 번째 숫자부터 비교해요</span>{'\nbest = numbers[0]\n'}<span className="auth-code-keyword">for</span>{' number in numbers:\n    '}<span className="auth-code-keyword">if</span>{' number > best:\n        best = number\n\n'}<span className="auth-code-keyword">print</span>{'(best)'}</code></pre>
     <div className="auth-preview-reflection"><span>작은 조건부터 확인해요</span><p>음수만 있는 입력에서도 원하는 결과가 나오는지 확인하고 제출해요.</p></div>
    </>}
    {i===1&&<>
     <div className="auth-preview-problem"><span>가장 큰 값 찾기 · 풀이 기록 예시</span><h3>맞혔지만, 설명할 수 있을까요?</h3><p>초깃값을 바꾸면 결과가 달라지는 이유를 돌아봐요.</p></div>
     <div className="auth-preview-notes"><div><span>확인한 조건</span><strong>모든 숫자가 음수인 경우</strong><p>0부터 비교하면 입력에 없는 값이 답이 될 수 있어요.</p></div><div><span>내 복습 메모</span><p>“첫 원소로 시작하는 이유를 이해했다. 빈 배열이 가능한지도 먼저 확인하자.”</p></div></div>
     <div className="auth-preview-reflection"><span>정답과 이해를 함께 기록해요</span><p>풀이 자신감과 복습 메모를 남기면 다시 살펴볼 문제를 찾기 쉬워요.</p></div>
    </>}
    {i===2&&<>
     <div className="auth-preview-problem"><span>조건 확인 · 연습 방향 예시</span><h3>초깃값에서 경계 조건으로</h3><p>한 문제에서 배운 생각을 다른 문제에도 적용해 봐요.</p></div>
     <ol className="auth-preview-training"><li><span>01</span><div><strong>최솟값도 찾아보기</strong><p>비교 방향이 바뀌어도 초깃값의 원리는 같을까요?</p></div></li><li><span>02</span><div><strong>같은 값이 여러 개라면</strong><p>최댓값의 위치를 찾을 때 어떤 위치를 남길까요?</p></div></li><li><span>03</span><div><strong>나에게 맞는 훈련 시작하기</strong><p>진단과 풀이 기록을 바탕으로 연습 방향을 잡아요.</p></div></li></ol>
     <div className="auth-preview-reflection"><span>한 문제를 다음 배움으로</span><p>문제를 많이 푸는 것만큼, 배운 내용을 다시 써보는 연습도 중요해요.</p></div>
    </>}
   </div>)}
  </div>
  <nav className="auth-preview-controls" aria-label="연습 예시 선택">
   <div>{slides.map((slide,i)=><button key={slide.label} type="button" aria-label={`${i+1}번째 예시 · ${slide.label}`} aria-pressed={index===i} onClick={()=>choose(i)}><span aria-hidden="true"/></button>)}</div>
   <button type="button" className="auth-preview-play" onClick={()=>setPaused(value=>!value)} aria-label={paused?'예시 자동 재생':'예시 자동 재생 일시정지'}>{paused?'재생':'일시정지'}</button>
  </nav>
 </section>;
}
