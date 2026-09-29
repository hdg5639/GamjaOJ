'use client';
import { useEffect, useState } from 'react';

const KEY = 'gamjaoj-editor-vim', EVENT = 'gamjaoj-editor-settings';
/** Vim key bindings on or off, remembered in this browser and shared by every editor on the page. */
export function useVimMode() {
  const [vim, setVimState] = useState(false);
  useEffect(() => {
    const read = () => { try { setVimState(localStorage.getItem(KEY) === 'on'); } catch { /* Off. */ } };
    read(); window.addEventListener(EVENT, read);
    return () => window.removeEventListener(EVENT, read);
  }, []);
  function setVim(next) {
    setVimState(next);
    try { localStorage.setItem(KEY, next ? 'on' : 'off'); } catch { /* This page only. */ }
    window.dispatchEvent(new Event(EVENT));
  }
  return [vim, setVim];
}
