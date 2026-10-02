'use client';
import { useEffect, useState } from 'react';
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
export function usePaneOrder(userId) {
  const key = `gamjaoj-pane-order-${userId}`, event = 'gamjaoj-pane-order';
  const [order, setOrder] = useState(ORDERS[0]);
  useEffect(() => {
    const read = () => { try { const saved = localStorage.getItem(key); setOrder(ORDERS.includes(saved) ? saved : ORDERS[0]); } catch {} };
    read(); window.addEventListener(event, read); window.addEventListener('storage', read);
    return () => { window.removeEventListener(event, read); window.removeEventListener('storage', read); };
  }, [key]);
  return [order, next => { setOrder(next); try { localStorage.setItem(key,next); } catch {} window.dispatchEvent(new Event(event)); }];
}

export function columnLayoutStyle(order, first, middle) {
  const panes = order.split('-');
  return { '--pane-first': `${first}fr`, '--pane-middle': `${(100-first)*middle/100}fr`, '--pane-last': `${(100-first)*(100-middle)/100}fr`,
    '--problem-column': panes.indexOf('problem')*2+1, '--code-column': panes.indexOf('code')*2+1, '--console-column': panes.indexOf('console')*2+1 };
}
export function columnScale(handle) {
  return 100 / Math.max(1,handle.closest('.practice-grid,.diagnostic-workspace').getBoundingClientRect().width-24);
}

export function SolvingLayoutChoice({ value, onChange, order, onOrderChange }) {
  return <div className="solving-layout-choice" role="group" aria-label="풀이 레이아웃">
    {['default','columns'].map(layout => <button key={layout} type="button" aria-label={layout==='default'?'기본 배치':'세로 3분할'} title={layout==='default'?'기본 배치 (ㅏ)':'세로 3분할'} aria-pressed={value===layout} onClick={()=>onChange(layout)}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/>
        {layout==='default'?<><path d="M10 4v16"/><path d="M10 12h11"/></>:<><path d="M9 4v16"/><path d="M15 4v16"/></>}</svg>
    </button>)}
    {value==='columns'&&<details className="pane-order"><summary aria-label="패널 순서 변경" title="패널 순서 변경">⇄</summary>
      <div className="pane-order-options" role="group" aria-label="왼쪽부터 패널 순서">{ORDERS.map(next=><button type="button" key={next} aria-pressed={order===next} onClick={e=>{onOrderChange(next);e.currentTarget.closest('details').open=false;}}>{next.split('-').map(p=>PANES[p]).join(' → ')}</button>)}</div>
    </details>}
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
        return 100/Math.max(1,handle.parentElement.getBoundingClientRect().height);
      }} onChange={onChange} className="split-handle" />
    <div className="split-bottom">{bottom}</div>
  </div>;
}
