'use client';

import { useEffect, useState } from 'react';
import Workspace from './workspace';
import AppHeader from './auto-header';

async function api(path, options = {}) {
  const headers = new Headers(options.headers);
  if (options.method && options.method !== 'GET') {
    const csrfResponse = await fetch('/api/auth/csrf', { cache: 'no-store' });
    if (!csrfResponse.ok) throw new Error('연결을 확인하고 다시 시도해 주세요.');
    const csrf = await csrfResponse.json();
    headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(path, { ...options, headers, cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const error = new Error(body.message || '요청을 완료하지 못했어요. 다시 시도해 주세요.');
    error.status = response.status;
    throw error;
  }
  return response.status === 204 || response.status === 201 ? null : response.json();
}

export default function Home() {
  const [sidebarCollapsed,setSidebarCollapsed]=useState(true);

  const [user, setUser] = useState(null);
  const [settings, setSettings] = useState(false);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState('login');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  async function refresh() {
    try { setUser(await api('/api/me')); }
    catch (e) { if (e.status !== 401) setError(e.message); setUser(null); }
    finally { setLoading(false); }
  }

  useEffect(() => {
    refresh();
    const sync = () => { setMessage(''); refresh(); };
    window.addEventListener('focus', sync);
    return () => window.removeEventListener('focus', sync);
  }, []);

  async function submit(event) {
    event.preventDefault();
    setBusy(true); setError(''); setMessage('');
    const form = event.currentTarget;
    const fields = Object.fromEntries(new FormData(form));
    try {
      if (mode === 'signup') {
        if (fields.password !== fields.confirmPassword) throw new Error('비밀번호가 서로 달라요.');
        const { confirmPassword, ...signup } = fields;
        await api('/api/auth/signup', {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(signup),
        });
        form.reset(); setMode('login'); setMessage('가입했어요! 방금 만든 계정으로 로그인해 주세요.');
      } else {
        await api('/api/auth/login', {
          method: 'POST', body: new URLSearchParams(fields),
        });
        form.reset(); await refresh();
      }
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  async function logout() {
    setBusy(true); setError(''); setMessage('');
    try {
      await api('/api/auth/logout', { method: 'POST' });
      setUser(null); setSettings(false); setMode('login'); setMessage('로그아웃했어요. 다음에 또 만나요.');
    } catch (e) { setError(e.message); if (e.status === 401) setUser(null); }
    finally { setBusy(false); }
  }

  async function withdraw(event) {
    event.preventDefault(); setBusy(true); setError(''); setMessage('');
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    try {
      await api('/api/me/delete', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(fields) });
      setUser(null); setSettings(false); setMode('login'); setMessage('탈퇴가 완료됐어요. 계정과 개인 기록을 모두 삭제했어요.');
    } catch (e) { setError(e.message); if (e.status === 401) setUser(null); }
    finally { setBusy(false); }
  }

  async function save(event) {
    event.preventDefault(); setBusy(true); setError(''); setMessage('');
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    try {
      setUser(await api('/api/me', {
        method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(fields),
      }));
      setMessage('내 연습 설정을 저장했어요.');
    } catch (e) { setError(e.message); if (e.status === 401) setUser(null); }
    finally { setBusy(false); }
  }

  useEffect(()=>{
    let collapsed=false; // Expanded unless this browser saved a choice for this account.
    try { const saved=localStorage.getItem(`gamjaoj-sidebar-${user?.id}`);if(saved!==null)collapsed=saved==='collapsed'; } catch {}
    setSidebarCollapsed(collapsed);
  },[user?.id]);
  function toggleSidebar(){
    const next=!sidebarCollapsed;setSidebarCollapsed(next);
    try{localStorage.setItem(`gamjaoj-sidebar-${user.id}`,next?'collapsed':'expanded');}catch{}
  }

  return <div className={`shell ${user ? 'signed-in' : ''}`} data-sidebar-collapsed={sidebarCollapsed}>
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    <AppHeader key={user?.id || 'anonymous'}><a href="/" className="brand"><img className="brand-symbol" src="/gamjaoj-favicon.svg" alt="" width="34" height="34"/><span>Gamja<span className="brand-accent">OJ</span></span></a>
      <span className="header-note">문제를 풀고, 나의 다음 단계를 찾다.</span>
      {user && <nav className="account-nav" aria-label="계정 메뉴"><span className="user-name">{user.nickname}님</span>
        <button className="secondary" onClick={() => setSettings(value => !value)}>{settings ? '문제 풀기' : '내 설정'}</button>
        <button className="secondary" onClick={logout} disabled={busy}>로그아웃</button></nav>}
    </AppHeader>
    <main id={user && !settings ? undefined : "main-content"} tabIndex={-1} hidden={!!user && !settings} className={user ? 'settings-page' : 'login-page'}>
      <section className="intro" hidden={!!user}>
        <span className="eyebrow">알고리즘 연습장</span>
        <h1>한 문제씩,<br/>내 것으로.</h1>
        <p>막혔던 개념도, 스스로 풀어낸 순간도.<br/>각자의 속도로 연습하고 함께 성장해요.</p>
        <div className="intro-path" aria-label="학습 흐름"><span>01 <strong>탐색</strong></span><span>02 <strong>풀이</strong></span><span>03 <strong>다음 훈련</strong></span></div><p className="intro-detail">함께 만든 문제, 선택 진단, 나에게 맞는 연습.<br/>Java · C++ · Python으로 한 곳에서 이어가세요.</p>
      </section>
      <section className="card" aria-label={user ? '내 계정' : '계정 시작하기'}>
        {loading ? <p role="status">내 연습장을 불러오고 있어요…</p> : user ? <>
          <h1 className="workspace-title">내 설정</h1>
          <p className="muted">@{user.username} · 오늘은 어떤 개념을 연습할까요?</p>
          <form key={user.id + user.nickname + user.trainingGoal} onSubmit={save}>
            <label>닉네임<input name="nickname" defaultValue={user.nickname} maxLength={24} required autoComplete="nickname" /></label>
            <label>연습하고 싶은 목표<textarea name="trainingGoal" defaultValue={user.trainingGoal} maxLength={120} rows={3} placeholder="예: DFS 방문 상태 복원, DP 점화식 세우기" /></label>
            <button className="primary" disabled={busy}>{busy ? '저장 중…' : '내 설정 저장'}</button>
          </form>
          <details className="account-deletion">
            <summary>회원 탈퇴</summary>
            <p>탈퇴하면 계정과 개인 기록을 바로 삭제하며 되돌릴 수 없어요. 제출·실행 기록, 훈련, 진단과 평가, AI 요청, 생성 요청, 비공개 문제와 규칙이 모두 지워져요.</p>
            <p>다른 회원에게 공유한 문제·규칙과 다른 회원이 이미 푼 문제는 그 회원들의 기록을 위해 작성자 표시 없이 남아요. 진행 중인 채점·출제·규칙 등록이 있으면 끝난 뒤 탈퇴할 수 있어요.</p>
            <form onSubmit={withdraw}>
              <label>비밀번호<input name="password" type="password" required maxLength={72} autoComplete="current-password" /></label>
              <label>확인을 위해 아이디 <strong>{user.username}</strong> 입력<input name="confirmation" required maxLength={24} autoComplete="off" pattern={user.username} title="아이디를 그대로 입력해 주세요." /></label>
              <button className="danger" disabled={busy}>{busy ? '처리 중…' : '계정 영구 삭제'}</button>
            </form>
          </details>

        </> : <>
          <div className="tabs" role="group" aria-label="로그인 또는 가입">
            <button aria-pressed={mode === 'login'} disabled={busy} className={mode === 'login' ? 'selected' : ''} onClick={() => { setMode('login'); setError(''); setMessage(''); }}>로그인</button>
            <button aria-pressed={mode === 'signup'} disabled={busy} className={mode === 'signup' ? 'selected' : ''} onClick={() => { setMode('signup'); setError(''); setMessage(''); }}>처음 왔어요</button>
          </div>
          <h2>{mode === 'login' ? '다시 만나 반가워요.' : '내 연습장을 만들어 볼까요?'}</h2>
          <p className="muted">{mode === 'login' ? '이어서 연습할 준비가 됐나요?' : '아이디와 비밀번호만 있으면 바로 시작할 수 있어요.'}</p>
          <form key={mode} onSubmit={submit}>
            {mode === 'signup' && <label>닉네임<input name="nickname" maxLength={24} required autoComplete="nickname" placeholder="어떻게 불러드릴까요?" /></label>}
            <label>아이디<input name="username" required minLength={3} maxLength={24} pattern="[a-z0-9_]{3,24}" autoComplete="username" autoCapitalize="none" spellCheck="false" placeholder="영문 소문자, 숫자, 밑줄 3~24자" /></label>
            <label>비밀번호<input name="password" type="password" required minLength={mode === 'signup' ? 8 : undefined} maxLength={72} autoComplete={mode === 'signup' ? 'new-password' : 'current-password'} placeholder="8자 이상 입력해 주세요" /></label>
            {mode === 'signup' && <label>비밀번호 확인<input name="confirmPassword" type="password" required maxLength={72} autoComplete="new-password" /></label>}
            <button className="primary" disabled={busy}>{busy ? '잠시만요…' : mode === 'login' ? '내 연습장으로' : '가입하기'}</button>
          </form>
          <p className="help">계정에 문제가 생겼다면 운영자에게 알려주세요.</p>
        </>}
        {error && <p role="alert" className="notice error">{error}</p>}
        {message && <p role="status" className="notice success">{message}</p>}
      </section>
    </main>
    {user && <main id={settings ? undefined : "main-content"} tabIndex={-1} className="app-main" hidden={settings}>
      {error && <p role="alert" className="notice error">{error}</p>}
      <Workspace key={user.id} user={user} api={api} sidebarCollapsed={sidebarCollapsed} onToggleSidebar={toggleSidebar} />
    </main>}
    <footer>GamjaOJ <span>잘하는 것보다, 어제보다 한 걸음.</span></footer>
  </div>;
}
