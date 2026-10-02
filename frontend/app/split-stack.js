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

export function SolvingLayoutChoice({ value, onChange }) {
  return <label className="solving-layout-choice"><span className="sr-only">풀이 레이아웃</span>
    <select aria-label="풀이 레이아웃" value={value} onChange={e => onChange(e.target.value)}>
      <option value="default">기본 배치 (ㅏ)</option><option value="columns">세로 3분할 (문제 | 코드 | 터미널)</option>
    </select></label>;
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
      scale={handle => 100 / Math.max(1, handle.parentElement.getBoundingClientRect()[columns ? 'width' : 'height'])} onChange={onChange} className="split-handle" />
    <div className="split-bottom">{bottom}</div>
  </div>;
}
