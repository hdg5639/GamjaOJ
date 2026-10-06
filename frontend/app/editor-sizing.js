'use client';
import {useEffect,useRef,useState} from 'react';
const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
export function useEditorSizing(userId,scope,defaultRatio=36,defaultHeight=null){
  const defaults={ratio:defaultRatio,height:defaultHeight};
  const key=`gamjaoj-editor-layout-v1-${userId}-${scope}`;
  const [size,setSize]=useState(defaults);
  useEffect(()=>{try{const saved=JSON.parse(localStorage.getItem(key));setSize({ratio:Number.isFinite(saved?.ratio)?clamp(saved.ratio,20,70):defaultRatio,height:Number.isFinite(saved?.height)?clamp(saved.height,160,1000):defaultHeight});}catch{setSize(defaults);}},[key]);
  function change(patch){setSize(previous=>{const next={...previous,...patch};try{localStorage.setItem(key,JSON.stringify(next));}catch{}return next;});}
  function reset(){setSize(defaults);try{localStorage.removeItem(key);}catch{}}
  return [size,change,reset];
}
export function ResizeHandle({label,orientation='vertical',value,min,max,step=1,onChange,scale=()=>1,className=''}){
  const ref=useRef(null),drag=useRef(null),frame=useRef(null),pending=useRef(null),change=useRef(onChange);
  change.current=onChange;
  function flush(){if(frame.current!==null)cancelAnimationFrame(frame.current);frame.current=null;if(pending.current!==null){const next=pending.current;pending.current=null;change.current(next);}}
  useEffect(()=>()=>{if(frame.current!==null)cancelAnimationFrame(frame.current);},[]);
  const [measured,setMeasured]=useState(390);
  useEffect(()=>{if(orientation!=='horizontal'||value!=null)return;const pane=ref.current?.previousElementSibling;if(!pane)return;const observer=new ResizeObserver(()=>setMeasured(Math.round(pane.getBoundingClientRect().height)));observer.observe(pane);return()=>observer.disconnect();},[orientation,value]);
  const current=value??measured;
  function end(event){flush();drag.current=null;if(event.currentTarget.hasPointerCapture(event.pointerId))event.currentTarget.releasePointerCapture(event.pointerId);}
  return <div ref={ref} className={`editor-resize-handle ${orientation} ${className}`} role="separator" tabIndex={0} aria-label={label} title={`${label} · 드래그 또는 방향키로 조절`} aria-orientation={orientation} aria-valuemin={min} aria-valuemax={max} aria-valuenow={Math.round(current)}
    onPointerDown={event=>{if(event.button!==0)return;event.preventDefault();event.currentTarget.focus();event.currentTarget.setPointerCapture(event.pointerId);drag.current={position:orientation==='vertical'?event.clientX:event.clientY,value:current,scale:scale(event.currentTarget)};}}
    onPointerMove={event=>{if(!drag.current)return;const position=orientation==='vertical'?event.clientX:event.clientY;pending.current=clamp(drag.current.value+(position-drag.current.position)*drag.current.scale,min,max);if(frame.current===null)frame.current=requestAnimationFrame(flush);}}
    onPointerUp={end} onPointerCancel={end} onLostPointerCapture={()=>{flush();drag.current=null;}}
    onKeyDown={event=>{const decrement=orientation==='vertical'?'ArrowLeft':'ArrowUp',increment=orientation==='vertical'?'ArrowRight':'ArrowDown';if(![decrement,increment,'Home','End'].includes(event.key))return;event.preventDefault();onChange(event.key==='Home'?min:event.key==='End'?max:clamp(current+(event.key===decrement?-step:step)*(event.shiftKey?5:1),min,max));}}><span aria-hidden="true"/></div>;
}
export function EditorSizing({size,onChange,onReset,diagnostic=false}){
 return <details className="editor-sizing"><summary>편집기 크기</summary><div className="sizing-controls">
  <label className={diagnostic?'diagnostic-ratio-control':'practice-ratio-control'}>문제 영역 비율 · {Math.round(size.ratio)}%<input aria-label="문제 영역 비율" type="range" min="20" max="70" value={size.ratio} onChange={e=>onChange({ratio:Number(e.target.value)})}/></label>
  <label>편집기 높이 · {size.height==null?'화면에 맞춤':`${Math.round(size.height)}px`}<input aria-label="편집기 높이" type="range" min="160" max="1000" step="20" value={size.height??400} onChange={e=>onChange({height:Number(e.target.value)})}/></label>
  <button type="button" className="secondary" onClick={onReset}>크기 초기화</button><small>경계선을 드래그하거나 방향키로 조절해요. 설정은 이 브라우저에 저장됩니다.</small>
 </div></details>;
}
export function splitScale(handle){const grid=handle.parentElement;const editor=grid.querySelector('.editor-column')||grid.querySelector('.diagnostic-code-column');const problem=grid.querySelector('article');return 100/(editor.getBoundingClientRect().width+problem.getBoundingClientRect().width);}
