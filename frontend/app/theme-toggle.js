'use client';
import { useEffect, useState } from 'react';

const KEY = 'gamjaoj-theme';
function saved() { try { const v = localStorage.getItem(KEY); return v === 'light' || v === 'dark' ? v : null; } catch { return null; } }
function system() { return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'; }
function apply(theme) { document.documentElement.setAttribute('data-theme', theme); }

/** Light/dark switch. Without a saved choice the page follows the operating system and keeps following it. */
export default function ThemeToggle() {
  const [theme, setTheme] = useState(null);
  useEffect(() => {
    setTheme(document.documentElement.getAttribute('data-theme') || saved() || system());
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const follow = () => { if (!saved()) { const next = system(); apply(next); setTheme(next); } };
    media.addEventListener('change', follow);
    return () => media.removeEventListener('change', follow);
  }, []);
  if (!theme) return null;
  const next = theme === 'dark' ? 'light' : 'dark';
  return <button type="button" className="secondary theme-toggle" aria-label={next === 'dark' ? '다크 모드로 전환' : '라이트 모드로 전환'}
    title={next === 'dark' ? '다크 모드로 전환' : '라이트 모드로 전환'}
    onClick={() => { apply(next); setTheme(next); try { localStorage.setItem(KEY, next); } catch { /* This visit only. */ } }}>
    <svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      {theme === 'dark'
        ? <><circle cx="12" cy="12" r="4.5"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></>
        : <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z"/>}
    </svg>
  </button>;
}
