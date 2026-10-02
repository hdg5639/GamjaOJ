'use client';
import { useEffect, useRef, useState } from 'react';
import { ResizeHandle } from './editor-sizing';

/** Layout preference is shared by practice and diagnostics for this account. */
export function useSolvingLayout(userId) {
  const key = `gamjaoj-solving-layout-${userId}`, event = 'gamjaoj-solving-layout';
  const [layout, setLayout] = useState('default');
  useEffect(() => {
    const read = () => { try { setLayout(localStorage.getItem(key) === 'columns' ? 'columns' : 'default'); } catch {} };
    read(); window.addEventListener(event, read); window.addEventListener('storage', read);
    return () => { window.removeEventListener(event, read); window.removeEventListener('storage', read); };
  }, [key]);
  function change(next) {
    setLayout(next);
    try { localStorage.setItem(key, next); } catch {}
    window.dispatchEvent(new Event(event));
  }
  return [layout, change];
}

const PANES = { problem: '문제', code: '코드', console: '터미널' };
const ORDERS = ['problem-code-console','problem-console-code','code-problem-console','code-console-problem','console-problem-code','console-code-problem'];
export function usePaneOrder(userId, layout = 'columns') {
  const key = `gamjaoj-pane-order-${userId}-${layout}`, event = 'gamjaoj-pane-order';
  const [preference, setPreference] = useState({order:ORDERS[0], mirrored:false});
  const latest=useRef(preference);
  useEffect(() => {
    const read = () => {
      let next={order:ORDERS[0], mirrored:false};
      try {
        const saved=JSON.parse(localStorage.getItem(key));
        if(ORDERS.includes(saved?.order)) next={order:saved.order,mirrored:layout==='default'&&saved.mirrored===true};
        else if(layout==='columns') {
          const legacy=localStorage.getItem(`gamjaoj-pane-order-${userId}`);
          if(ORDERS.includes(legacy))next.order=legacy;
        }
      } catch {}
      latest.current=next;setPreference(next);
    };
    read(); window.addEventListener(event, read); window.addEventListener('storage', read);
    return () => { window.removeEventListener(event, read); window.removeEventListener('storage', read); };
  }, [key]);
  function change(patch) {
    const next={...latest.current,...patch};latest.current=next;setPreference(next);
    try { localStorage.setItem(key,JSON.stringify(next)); } catch { return; }
    window.dispatchEvent(new Event(event));
  }
  return [preference.order, order=>change({order}), preference.mirrored, mirrored=>change({mirrored})];
}

export function columnLayoutStyle(order, first, middle, mirrored=false, full=36, upper=65) {
  const panes = order.split('-');
  const style={ '--pane-first': `${first}fr`, '--pane-middle': `${(100-first)*middle/100}fr`, '--pane-last': `${(100-first)*(100-middle)/100}fr`,
    '--problem-column': panes.indexOf('problem')*2+1, '--code-column': panes.indexOf('code')*2+1, '--console-column': panes.indexOf('console')*2+1,
    '--default-left':`${mirrored?100-full:full}fr`, '--default-right':`${mirrored?full:100-full}fr`,
    '--stack-upper':`${upper}fr`, '--stack-lower':`${100-upper}fr`, '--stack-column':mirrored?1:3 };
  panes.forEach((pane,index)=>{
    const column=index===0?(mirrored?3:1):(mirrored?1:3), start=index===2?5:1, end=index===0?8:index===1?4:8;
    style[`--${pane}-default-column`]=column;
    style[`--${pane}-default-start`]=start;style[`--${pane}-default-end`]=end;
    if(pane==='code') {
      style['--code-header-row']=start;
      style['--code-body-start']=start+1;
      style['--code-body-end']=end-1;
      style['--code-footer-row']=end-1;
    }
  });
  return style;
}
export function columnScale(handle) {
  return 100 / Math.max(1,handle.closest('.practice-grid,.diagnostic-workspace').getBoundingClientRect().width-24);
}

function PaneOrderEditor({layout,order,onChange,mirrored,onMirrorChange}) {
  const [picked,setPicked]=useState(null),[hover,setHover]=useState(null),[announcement,setAnnouncement]=useState('');
  const dragging=useRef(null),touch=useRef(null),host=useRef(null),suppressClick=useRef(false);
  const panes=order.split('-');
  const positions=layout==='columns'?['왼쪽','가운데','오른쪽']:[mirrored?'오른쪽 전체':'왼쪽 전체',mirrored?'왼쪽 위':'오른쪽 위',mirrored?'왼쪽 아래':'오른쪽 아래'];
  function reset(){dragging.current=null;touch.current=null;setPicked(null);setHover(null);}
  function swap(from,to){
    if(from==null||from===to){reset();return;}
    const next=[...panes];[next[from],next[to]]=[next[to],next[from]];
    onChange(next.join('-'));setAnnouncement(`${PANES[panes[from]]}와 ${PANES[panes[to]]} 위치를 바꿨어요.`);reset();
  }
  function pick(index){if(picked==null)setPicked(index);else swap(picked,index);}
  return <div className="pane-order-editor">
    <div className="pane-order-title"><strong>패널 위치</strong>{layout==='default'&&<button type="button" className="pane-mirror" aria-pressed={mirrored} onClick={()=>{onMirrorChange(!mirrored);reset();}}>좌우 반전</button>}</div>
    <div className="pane-order-preview" data-layout={layout} data-mirrored={mirrored} ref={host} role="group" aria-label="패널 위치 교환">
      {panes.map((pane,index)=><button type="button" key={index} data-pane-slot={index} data-pane={pane} data-drop-target={hover===index&&picked!==index} aria-label={`${positions[index]} 패널: ${PANES[pane]}`} aria-pressed={picked===index}
        onClick={()=>{if(suppressClick.current){suppressClick.current=false;return;}pick(index);}}
        onDragStart={e=>{dragging.current=index;setPicked(index);e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',pane);}}
        onDragOver={e=>{if(dragging.current!=null){e.preventDefault();e.dataTransfer.dropEffect='move';setHover(index);}}}
        onDrop={e=>{e.preventDefault();swap(dragging.current,index);}} onDragEnd={reset}
        onPointerDown={e=>{suppressClick.current=false;touch.current={index,x:e.clientX,y:e.clientY,moved:false};e.currentTarget.setPointerCapture(e.pointerId);}}
        onPointerMove={e=>{if(!touch.current)return;if(Math.hypot(e.clientX-touch.current.x,e.clientY-touch.current.y)>5){touch.current.moved=true;setPicked(touch.current.index);}const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-pane-slot]');setHover(target&&host.current.contains(target)?Number(target.dataset.paneSlot):null);}}
        onPointerUp={e=>{if(!touch.current)return;const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-pane-slot]');if(touch.current.moved&&target&&host.current.contains(target)){suppressClick.current=true;swap(touch.current.index,Number(target.dataset.paneSlot));}else {touch.current=null;setHover(null);}if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId);}}
        onPointerCancel={reset}
        onKeyDown={e=>{if(e.key==='Escape'){e.preventDefault();reset();}if(['ArrowLeft','ArrowUp','ArrowRight','ArrowDown'].includes(e.key)){e.preventDefault();const next=(index+(['ArrowLeft','ArrowUp'].includes(e.key)?2:1))%3;host.current.querySelector(`[data-pane-slot="${next}"]`)?.focus();}}}>
        <span className="pane-grip" aria-hidden="true">⠿</span><strong>{PANES[pane]}</strong><small>{positions[index]}</small>
      </button>)}
    </div>
    <p>끌어서 서로 교환하세요. 클릭 두 번으로도 바꿀 수 있어요.</p><span className="sr-only" role="status">{announcement}</span>
    <button type="button" className="pane-order-reset" onClick={()=>{onChange(ORDERS[0]);onMirrorChange?.(false);reset();}}>기본 위치로</button>
  </div>;
}

export function SolvingLayoutChoice({ value, onChange, order, onOrderChange, mirrored=false, onMirrorChange }) {
  return <div className="solving-layout-choice" role="group" aria-label="풀이 레이아웃">
    {['default','columns'].map(layout => <button key={layout} type="button" aria-label={layout==='default'?'기본 배치':'세로 3분할'} title={layout==='default'?'기본 배치 (ㅏ)':'세로 3분할'} aria-pressed={value===layout} onClick={()=>onChange(layout)}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/>
        {layout==='default'?<><path d="M10 4v16"/><path d="M10 12h11"/></>:<><path d="M9 4v16"/><path d="M15 4v16"/></>}</svg>
    </button>)}
    <details className="pane-order"><summary aria-label="패널 순서 변경" title="패널 위치 변경">⇄</summary>
      <PaneOrderEditor key={value} layout={value} order={order} onChange={onOrderChange} mirrored={mirrored} onMirrorChange={onMirrorChange}/>
    </details>
  </div>;
}

/** Editor share (percent) of an editor/console split, remembered per account and screen in this browser. */
export function useSplit(userId, screen, fallback = 65) {
  const key = `gamjaoj-split-${screen}-${userId}`;
  const [share, setShare] = useState(fallback);
  useEffect(() => {
    try { const saved = Number(localStorage.getItem(key)); if (saved >= 20 && saved <= 88) setShare(saved); } catch { /* Default. */ }
  }, [key]);
  function change(value) {
    const next = Math.round(Math.max(20, Math.min(88, value)));
    setShare(next);
    try { localStorage.setItem(key, String(next)); } catch { /* This page only. */ }
  }
  return [share, change];
}

/** One bar between the editor and the run console: dragging it moves space from one to the other. */
export default function SplitStack({ top, bottom, share, onChange, layout = 'default', label = '편집기와 실행 결과 비율', className = '' }) {
  const [wide, setWide] = useState(false);
  useEffect(() => {
    const media = window.matchMedia('(min-width: 851px)');
    const update = () => setWide(media.matches);
    update(); media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  const columns = layout === 'columns' && wide;
  return <div className={`split-stack ${className}`} data-layout={columns ? 'columns' : 'default'} style={columns
    ? { gridTemplateColumns: `minmax(0, ${share}fr) auto minmax(0, ${100 - share}fr)`, gridTemplateRows: 'minmax(0,1fr)' }
    : { gridTemplateRows: `minmax(96px, ${share}fr) auto minmax(64px, ${100 - share}fr)` }}>
    <div className="split-top">{top}</div>
    <ResizeHandle label={label} orientation={columns ? 'vertical' : 'horizontal'} value={share} min={20} max={88} step={2}
      scale={handle => {
        if (columns) {
          const grid=handle.closest('.practice-grid,.diagnostic-workspace');
          const tracks=getComputedStyle(grid).gridTemplateColumns.split(' ').map(parseFloat);
          return 100/Math.max(1,tracks[2]+tracks[4]);
        }
        const grid=handle.closest('.practice-grid,.diagnostic-workspace');
        return 100/Math.max(1,getComputedStyle(grid).getPropertyValue('--pane-grid-ready').trim()==='1'?grid.getBoundingClientRect().height:handle.parentElement.getBoundingClientRect().height);
      }} onChange={onChange} className="split-handle" />
    <div className="split-bottom">{bottom}</div>
  </div>;
}
