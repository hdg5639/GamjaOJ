'use client';
import { useEffect, useState } from 'react';
import { ResizeHandle } from './editor-sizing';

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
export default function SplitStack({ top, bottom, share, onChange, label = '편집기와 실행 결과 비율', className = '' }) {
  return <div className={`split-stack ${className}`} style={{ gridTemplateRows: `minmax(96px, ${share}fr) auto minmax(64px, ${100 - share}fr)` }}>
    <div className="split-top">{top}</div>
    <ResizeHandle label={label} orientation="horizontal" value={share} min={20} max={88} step={2}
      scale={handle => 100 / Math.max(1, handle.parentElement.getBoundingClientRect().height)} onChange={onChange} className="split-handle" />
    <div className="split-bottom">{bottom}</div>
  </div>;
}
