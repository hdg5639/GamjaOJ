'use client';
import {useLayoutEffect,useRef,useState} from 'react';
import {createPortal} from 'react-dom';

const storageKey='gamjaoj-login-intro-v1';
const duration=2400;
// One timeline for the flying wordmark and both content columns. Landing uses
// the actual header rectangle rather than a viewport-specific pixel offset.
export default function LoginIntro({onPhaseChange}){
 const [flight,setFlight]=useState(null),finishRef=useRef(()=>{});
 useLayoutEffect(()=>{
  const motion=window.matchMedia('(prefers-reduced-motion: reduce)');
  let seen=false;try{seen=sessionStorage.getItem(storageKey)==='1';}catch{}
  const target=document.querySelector('.shell:not(.signed-in) .brand');
  if(seen||motion.matches||window.innerWidth<1024||!target){onPhaseChange('done');return;}
  try{sessionStorage.setItem(storageKey,'1');}catch{}
  const rect=target.getBoundingClientRect(),font=getComputedStyle(target),scale=2.8;
  setFlight({left:rect.left,top:rect.top,width:rect.width*scale,height:rect.height*scale,
   '--flight-x':`${innerWidth/2-rect.left-rect.width*scale/2}px`,
   '--flight-y':`${innerHeight/2-rect.top-rect.height*scale/2}px`,
   fontFamily:font.fontFamily,fontSize:`${parseFloat(font.fontSize)*scale}px`,fontWeight:font.fontWeight,
   letterSpacing:`${parseFloat(font.letterSpacing||'0')*scale||0}px`});
  onPhaseChange('playing');
  let ended=false,timer;
  const finish=()=>{
   if(ended)return;ended=true;clearTimeout(timer);
   window.removeEventListener('resize',finish);window.removeEventListener('scroll',finish);
   document.removeEventListener('keydown',key);document.removeEventListener('visibilitychange',visibility);
   motion.removeEventListener('change',finish);
   setFlight(null);onPhaseChange('done');
  };
  const key=e=>{if(e.key==='Escape'||e.key==='Tab')finish();};
  const visibility=()=>{if(document.hidden)finish();};
  finishRef.current=finish;
  window.addEventListener('resize',finish);window.addEventListener('scroll',finish,{passive:true});
  document.addEventListener('keydown',key);document.addEventListener('visibilitychange',visibility);
  motion.addEventListener('change',finish);timer=setTimeout(finish,duration+100);
  return ()=>{ended=true;clearTimeout(timer);window.removeEventListener('resize',finish);window.removeEventListener('scroll',finish);document.removeEventListener('keydown',key);document.removeEventListener('visibilitychange',visibility);motion.removeEventListener('change',finish);};
 },[onPhaseChange]);
 if(!flight)return null;
 return createPortal(<div className="auth-intro-overlay">
  <div className="auth-intro-wash" aria-hidden="true"/>
  <div className="auth-brand-flight" style={flight} aria-hidden="true">
   <img src="/gamjaoj-symbol.svg?v=hex-check-v1" width="95.2" height="95.2" alt=""/>
   <span className="auth-brand-text">Gamja<span className="brand-accent">OJ</span></span>
  </div>
  <button className="auth-intro-skip secondary" type="button" onClick={()=>finishRef.current()}>애니메이션 건너뛰기</button>
 </div>,document.body);
}
