'use client';
import LoadingIndicator from './loading-indicator';
import LoginIntro from './login-intro';
import AnnouncementCenter from './announcement-center';
import AuthLearningPreview from './auth-learning-preview';

import { useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
const Workspace = dynamic(() => import('./workspace'));
import AppHeader from './auto-header';
import ThemeToggle from './theme-toggle';
import AppearanceSettings from './appearance-settings';
import SiteNotice from './site-notice';
const IntegrationsPanel = dynamic(() => import('./integrations-panel'));
import {cachedApi} from './client-cache.mjs';

async function request(path, options = {}) {
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

const api = cachedApi(request);

export default function Home() {
  const [sidebarCollapsed,setSidebarCollapsed]=useState(true);
  const [loginIntro,setLoginIntro]=useState('done');

  const [user, setUser] = useState(null);
  const [settings, setSettings] = useState(false);
  const [settingsOpened, setSettingsOpened] = useState(false);
  useEffect(()=>{if(settings)setSettingsOpened(true);},[settings]);
  useEffect(()=>{api.clear();if(!user)setSettingsOpened(false);},[user?.id]);
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
    const url=new URL(window.location.href);
    if(url.searchParams.get('settings')==='integrations'){
      setSettings(true);
      if(url.searchParams.has('connected'))setMessage('계정을 연결했어요. 저장 위치를 선택하고 자동 저장을 켜 주세요.');
      if(url.searchParams.has('connectionError'))setError('계정을 연결하지 못했어요. 연결을 다시 시작해 주세요.');
      url.searchParams.delete('connected');url.searchParams.delete('connectionError');
      window.history.replaceState(null,'',url);
    }
    refresh();
    const sync = () => { api.clear(); setMessage(''); refresh(); };
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

  return <div className={`shell ${user ? 'signed-in' : ''}`} data-sidebar-collapsed={sidebarCollapsed} data-auth-mode={mode} data-login-intro={user?'done':loading?'pending':loginIntro}>
    <a className="skip-link" href="#main-content">본문으로 이동</a>
    <AppHeader key={user?.id || 'anonymous'}><a href="/" className="brand"><img className="brand-symbol" src="/gamjaoj-favicon.svg?v=hex-check-v1" alt="" width="34" height="34"/><span>Gamja<span className="brand-accent">OJ</span></span></a>
      <span className="header-note">문제를 풀고, 나의 다음 단계를 찾다.</span>
      {user ? <nav className="account-nav" aria-label="계정 메뉴"><span className="user-name">{user.nickname}님</span>
        <AnnouncementCenter accountId={user.id}/><ThemeToggle/><AppearanceSettings/>
        <button className="secondary" onClick={() => setSettings(value => !value)}>{settings ? '문제 풀기' : '내 설정'}</button>
        <button className="secondary" onClick={logout} disabled={busy}>로그아웃</button></nav>
        : <span className="anonymous-theme"><AnnouncementCenter/><ThemeToggle/><AppearanceSettings/></span>}
    </AppHeader>
    <main id={user && !settings ? undefined : "main-content"} tabIndex={-1} hidden={!!user && !settings} className={user ? 'settings-page' : 'login-page'}>
      <section className="intro auth-story" hidden={!!user} aria-labelledby="auth-story-title">
        <div className="auth-story-copy">
          <span className="eyebrow">GamjaBox에서 이어지는 알고리즘 연습장</span>
          <h1 id="auth-story-title">한 문제씩,<br/><span>내 것으로.</span></h1>
          <p>풀어낸 문제는 기록으로,<br/>막혔던 순간은 다음 연습으로 이어집니다.</p>
          <div className="auth-story-services">
            <p className="auth-language-line" aria-label="지원 언어"><span>Java</span><span>C++</span><span>Python</span><span className="auth-language-caption">익숙한 언어로, 한 곳에서.</span></p>
            <dl>
              <div><dt>내 실력을 살피는 진단</dt><dd>기초부터 A·B형 목표 진단까지.</dd></div>
              <div><dt>다음 문제까지 이어지는 훈련</dt><dd>진단 기반 계획과 알고리즘별 훈련 코스.</dd></div>
              <div><dt>흩어지지 않는 풀이 기록</dt><dd>성장 겹·활동 잔디, GitHub·Notion 저장.</dd></div>
            </dl>
          </div>
        </div>
        <AuthLearningPreview visible={!user}/>
        <ol className="auth-learning-path" aria-label="학습 흐름">
          <li><span className="auth-step-number">01</span><div><h2>내 방식으로 풀고</h2><p>Java · C++ · Python으로 작성하고,<br/>실제 실행 결과를 확인해요.</p></div></li>
          <li><span className="auth-step-number">02</span><div><h2>한 번 더 돌아보고</h2><p>풀이 자신감과 AI 피드백으로<br/>이해한 부분과 헷갈린 부분을 구분해요.</p></div></li>
          <li><span className="auth-step-number">03</span><div><h2>다음 연습으로 이어가요</h2><p>진단과 풀이 기록을 바탕으로<br/>나에게 맞는 훈련을 시작해요.</p></div></li>
        </ol>
        <div className="auth-story-footer"><span>정답을 넘어, 내 실력으로.</span><small>GamjaBox Family · GamjaOJ</small></div>
      </section>
      <section className={user ? 'settings-container' : 'card'} aria-label={user ? '내 계정' : '계정 시작하기'}>
        {loading ? <LoadingIndicator>내 연습장을 불러오고 있어요…</LoadingIndicator> : user ? <>
          <header className="settings-heading">
            <div><h1 className="workspace-title">내 설정</h1><p className="muted">프로필과 연습 목표, 풀이를 기록할 곳을 관리해요.</p></div>
            <span className="settings-username">@{user.username}</span>
          </header>
          {error && <p role="alert" className="notice error">{error}</p>}
          {message && <p role="status" className="notice success">{message}</p>}
          <div className="settings-layout">
            <nav className="settings-navigation" aria-label="설정 항목">
              <a href="#settings-profile">프로필과 연습 목표</a>
              <a href="#integrations-heading">풀이 자동 저장</a>
              <a href="#export-history-heading">최근 저장 내역</a>
              <a href="#settings-account">계정 관리</a>
            </nav>
            <div className="settings-content">
          <section className="settings-section settings-profile" aria-labelledby="settings-profile">
            <div className="settings-section-heading"><h2 id="settings-profile" tabIndex={-1}>프로필과 연습 목표</h2><p className="muted">GamjaOJ에서 사용할 이름과 다음 연습 목표를 정해요.</p></div>
          <form key={user.id + user.nickname + user.trainingGoal} onSubmit={save}>
            <label>닉네임<input name="nickname" defaultValue={user.nickname} maxLength={24} required autoComplete="nickname" /></label>
            <label>연습하고 싶은 목표<textarea name="trainingGoal" defaultValue={user.trainingGoal} maxLength={120} rows={3} placeholder="예: DFS 방문 상태 복원, DP 점화식 세우기" /></label>
            <button className="primary" disabled={busy}>{busy ? '저장 중…' : '내 설정 저장'}</button>
          </form>
          </section>
          {(settings||settingsOpened)&&<IntegrationsPanel key={user.id} api={api}/>}
          <section className="settings-section settings-account" aria-labelledby="settings-account">
            <div className="settings-section-heading"><h2 id="settings-account" tabIndex={-1}>계정 관리</h2><p className="muted">계정 삭제 전 삭제되는 기록과 남는 자료를 확인해 주세요.</p></div>
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
          </section>
            </div>
          </div>
        </> : <>
          <p className="auth-form-eyebrow">나의 알고리즘 연습장</p>
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
          <section className="auth-start-guide" aria-label="첫 연습 안내">
            <h3>처음이라면, 이렇게 시작해요.</h3>
            <dl><div><dt>바로 문제 풀기</dt><dd>익숙한 언어와 관심 있는 유형부터.</dd></div><div><dt>진단으로 방향 잡기</dt><dd>어디서 시작할지 고민된다면.</dd></div></dl>
          </section>
          <p className="help">계정에 문제가 생겼다면 운영자에게 알려주세요.</p>
        </>}
        {!user && error && <p role="alert" className="notice error">{error}</p>}
        {!user && message && <p role="status" className="notice success">{message}</p>}
      </section>
    </main>
    {user && <main id={settings ? undefined : "main-content"} tabIndex={-1} className="app-main" hidden={settings}>
      {error && <p role="alert" className="notice error">{error}</p>}
      <Workspace key={user.id} user={user} api={api} sidebarCollapsed={sidebarCollapsed} onToggleSidebar={toggleSidebar} />
    </main>}
    {!loading&&!user&&<LoginIntro onPhaseChange={setLoginIntro}/>}
    <footer><div className="footer-line">GamjaOJ <span>잘하는 것보다, 어제보다 한 걸음.</span></div><SiteNotice/></footer>
  </div>;
}
