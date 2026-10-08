'use client';
import {useEffect, useRef, useState} from 'react';
import ThemeToggle from '../theme-toggle';
import LoadingIndicator from '../loading-indicator';
import './control.css';

async function api(path, options = {}) {
  const headers = new Headers(options.headers);
  if (options.method && options.method !== 'GET') {
    const csrf = await fetch('/api/auth/csrf', {cache: 'no-store'});
    if (!csrf.ok) throw new Error('연결을 확인해 주세요.');
    const token = await csrf.json(); headers.set(token.headerName, token.token);
  }
  const response = await fetch(path, {...options, headers, cache: 'no-store'});
  if (!response.ok) {
    const error = new Error(response.status === 403 ? '관리자 권한이 없는 계정이에요.' : '요청을 완료하지 못했어요. 다시 시도해 주세요.');
    error.status = response.status; throw error;
  }
  return response.status === 204 ? null : response.json();
}
const labels = {QUEUED:'대기', RUNNING:'처리 중', FINISHED:'완료', FAILED:'실패', READY:'준비 완료', COMPLETED:'완료', CANCELLED:'취소'};
function Queue({title, values}) {
  const rows = Object.entries(values);
  return <section className="control-section"><h2>{title}</h2><table><thead><tr><th scope="col">작업 상태</th><th scope="col">건수</th></tr></thead><tbody>{rows.length ? rows.map(([status,count]) => <tr key={status}><th scope="row">{labels[status] || status}</th><td>{count.toLocaleString()}</td></tr>) : <tr><td colSpan="2">등록된 작업이 없어요.</td></tr>}</tbody></table></section>;
}
export default function ControlOJ() {
  const [phase,setPhase] = useState('loading');
  const [user,setUser] = useState(null), [data,setData] = useState(null);
  const [busy,setBusy] = useState(false), [error,setError] = useState('');
  const inFlight = useRef(false);
  async function refresh() {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError('');
    try {
      const identity = await api('/api/admin/me');
      setUser(identity); setPhase('ready');
      setData(await api('/api/admin/overview'));
    } catch(e) {
      if (e.status === 401 || e.status === 403) {
        setUser(null); setData(null); setPhase(e.status === 401 ? 'login' : 'denied');
      } else { setError(e.message); setPhase(p => p === 'loading' ? 'error' : p); }
    } finally { inFlight.current = false; setBusy(false); }
  }
  useEffect(() => {document.title='ControlOJ · 운영 콘솔'; refresh();}, []);
  async function login(event) {
    event.preventDefault(); if (busy) return;
    const form = new FormData(event.currentTarget); setBusy(true); setError('');
    try {
      await api('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(form)});
      await refresh();
    } catch(e) {setError(e.status === 401 ? '아이디 또는 비밀번호를 확인해 주세요.' : e.message);}
    finally {setBusy(false);}
  }
  async function logout() {
    setBusy(true); setError('');
    try {await api('/api/auth/logout',{method:'POST'});setUser(null);setData(null);setPhase('login');}
    catch(e) {setError(e.message);} finally {setBusy(false);}
  }
  return <div className="control-shell">
    <header className="control-header"><a href="/" className="control-brand"><img src="/gamjaoj-favicon.svg" alt="" width="30" height="30"/>Control<span>OJ</span></a><div className="control-header-actions"><ThemeToggle/>{user && <><span>{user.username}</span><button className="secondary" disabled={busy} onClick={logout}>로그아웃</button></>}</div></header>
    <main className="control-main">
      {phase === 'loading' ? <div className="control-entry" role="status"><LoadingIndicator/>운영 콘솔 연결 중</div> : phase === 'login' ? <section className="control-entry control-glass"><p className="control-eyebrow">GAMJAOJ OPERATIONS</p><h1>운영 콘솔 로그인</h1><p>관리자로 지정된 GamjaOJ 계정으로 접속하세요.</p><form onSubmit={login}><label>아이디<input name="username" autoComplete="username" required/></label><label>비밀번호<input name="password" type="password" autoComplete="current-password" required/></label><button className="primary" disabled={busy}>{busy ? '확인 중…' : 'ControlOJ 로그인'}</button></form></section> : phase === 'denied' ? <section className="control-entry control-glass"><h1>관리자 권한이 필요해요</h1><p>이 계정은 운영 콘솔 접근 권한이 없어요.</p><button className="secondary" onClick={logout} disabled={busy}>로그아웃하고 다른 계정으로 로그인</button></section> : <>
      <div className="control-heading"><div><p className="control-eyebrow">GAMJAOJ OPERATIONS</p><h1>운영 현황</h1><p>{data ? `${new Date(data.measuredAt).toLocaleString('ko-KR')} 기준` : '서버에 기록된 운영 상태를 확인합니다.'}</p></div><button className="secondary" onClick={refresh} disabled={busy}>{busy ? '조회 중…' : '새로고침'}</button></div>
      {data && <div className="control-glass control-board"><dl className="control-totals"><div><dt>등록 회원</dt><dd>{data.members.toLocaleString()}<small>명</small></dd></div><div><dt>공개 준비된 문제</dt><dd>{data.problems.toLocaleString()}<small>개</small></dd></div><div><dt>채점 대기</dt><dd>{(data.judgeQueue.QUEUED || 0).toLocaleString()}<small>건</small></dd></div><div><dt>채점 처리 중</dt><dd>{(data.judgeQueue.RUNNING || 0).toLocaleString()}<small>건</small></dd></div></dl><div className="control-queues"><Queue title="제출·실행" values={data.judgeQueue}/><Queue title="문제 생성" values={data.generationJobs}/><Queue title="AI 분석" values={data.aiTasks}/></div></div>}
      {phase === 'error' && <button className="secondary" disabled={busy} onClick={refresh}>연결 다시 시도</button>}
      </>}
      {error && <p className="control-error" role="alert">{error}</p>}
    </main><footer className="control-footer">ControlOJ · GamjaOJ 운영 콘솔</footer>
  </div>;
}
