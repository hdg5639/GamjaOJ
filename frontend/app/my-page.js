'use client';
import {useEffect,useRef,useState} from 'react';
import ProblemId,{shortProblemId} from './problem-id';
import ListPagination from './list-pagination';
import ExecutionMetrics from './execution-metrics';
import {recordLanguageLabel} from './languages';
import {verdictText,verdictHelp} from './verdicts';

export default function MyPage({api,user,problems,onChoose,onDiagnostic}) {
 const pageSize=20;
 const moving=useRef(false);
 const [tab,setTab]=useState('problems'),[page,setPage]=useState(0),[summary,setSummary]=useState(null),[items,setItems]=useState([]),[total,setTotal]=useState(0),[detail,setDetail]=useState(null),[detailId,setDetailId]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[refresh,setRefresh]=useState(0);
 useEffect(()=>{let live=true;setBusy(true);setError('');setItems([]);setDetailId(null);setDetail(null);
  Promise.all([api('/api/my/summary'),api(tab==='problems'?`/api/my/problems?page=${page}`:`/api/submissions?page=${page}&size=${pageSize}`)]).then(([stats,data])=>{if(live){const count=tab==='problems'?data.total:stats.submitted,last=Math.max(0,Math.ceil(count/pageSize)-1);setSummary(stats);setTotal(count);if(page>last){setPage(last);return;}setItems(tab==='problems'?data.items:data);}}).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setBusy(false);});return()=>{live=false;};
 },[tab,page,refresh,user.id]);
 useEffect(()=>{let live=true;setDetail(null);if(detailId)api(`/api/submissions/${detailId}`).then(value=>{if(live)setDetail(value);}).catch(e=>{if(live)setError(e.message);});return()=>{live=false;};},[detailId,user.id]);
 useEffect(()=>{if(detail)requestAnimationFrame(()=>{const panel=document.getElementById('my-submission-detail');panel?.scrollIntoView({block:'nearest'});panel?.focus({preventScroll:true});});},[detail]);
 const pages=Math.max(1,Math.ceil(total/pageSize));
 function changePage(value){moving.current=true;setPage(value-1);}
 useEffect(()=>{if(!busy&&moving.current){moving.current=false;const results=document.getElementById('my-records-heading');results?.scrollIntoView({block:'start'});results?.focus({preventScroll:true});}},[busy,items]);
 return <section className="my-activity" aria-label="마이페이지">
  <div className="my-activity-heading"><div><h2>{user.nickname}님의 풀이 기록</h2><p className="muted">정식 제출을 기준으로 모았어요. 직접 실행은 기록에 포함하지 않아요.</p></div><button className="secondary" disabled={busy} onClick={()=>setRefresh(x=>x+1)}>기록 새로고침</button></div>
  {summary&&<dl className="my-stats"><div><dt>정답을 맞힌 문제</dt><dd>{summary.solvedProblems}</dd></div><div><dt>풀어본 문제</dt><dd>{summary.attemptedProblems}</dd></div><div><dt>정식 제출</dt><dd>{summary.submitted}</dd></div></dl>}
  <nav className="my-tabs" aria-label="내 기록 종류">{[['problems','풀어본 문제'],['submissions','전체 제출']].map(([key,label])=><button key={key} aria-pressed={tab===key} onClick={()=>{setTab(key);setPage(0);}}>{label}</button>)}</nav>
  {error&&<p role="alert" className="notice error">{error}</p>}
  {busy&&<p role="status">기록을 불러오는 중…</p>}
  {!busy&&!error&&!items.length&&<p className="muted">아직 정식 제출 기록이 없어요. 문제를 풀고 제출하면 여기에 모입니다.</p>}
  <div className="my-record-toolbar"><p id="my-records-heading" tabIndex={-1} className="muted" role="status">{busy?'기록 확인 중…':`${tab==='problems'?'풀어본 문제':'전체 제출'} ${total}개${total>0?` · ${page*pageSize+1}–${Math.min((page+1)*pageSize,total)}번째`:''}`}</p><ListPagination page={page+1} pages={pages} onChange={changePage} label="상단 기록 페이지" disabled={busy}/></div>
  <ul className="my-records">{items.map(item=>tab==='problems'?<li key={item.version}><div><strong>{item.title}</strong><small><ProblemId version={item.version}/></small><span>{item.held?'검토 보류':item.accepted>0?'정답':'도전 중'} · 제출 {item.attempts}회{item.diagnostic?' · 진단':''}</span></div><div className="my-record-actions"><time>{new Date(item.lastSubmitted).toLocaleDateString('ko-KR')}</time>{!item.held&&problems.some(p=>p.version===item.version)&&<button className="secondary" onClick={()=>onChoose(item.version)}>문제 풀기</button>}{item.diagnostic&&<button className="secondary" onClick={onDiagnostic}>진단으로</button>}</div></li>:<li key={item.id}><button className="my-submission" aria-pressed={detailId===item.id} onClick={()=>setDetailId(item.id)}><span><strong>{problems.find(p=>p.version===item.problemVersion)?.title||item.problemVersion}</strong><small>{shortProblemId(item.problemVersion)} · {recordLanguageLabel(item)}</small><time>{new Date(item.createdAt).toLocaleString('ko-KR')}</time></span><span className={`verdict ${item.verdict||''}`} title={verdictHelp[item.verdict]}>{verdictText(item.verdict)|| (item.status==='RUNNING'?'채점 중':'대기')}{item.problemHeld?' · 보류':''}</span></button></li>)}</ul>
  <ListPagination page={page+1} pages={pages} onChange={changePage} label="기록 페이지" disabled={busy}/>
  {detailId&&!detail&&!error&&<p role="status">제출 코드를 불러오는 중…</p>}
  {detail&&<section id="my-submission-detail" tabIndex={-1} className="my-submission-detail" aria-label="제출 상세"><div className="my-activity-heading"><h3>{verdictText(detail.verdict)||'채점 중'} · {recordLanguageLabel(detail)}</h3><button className="secondary" onClick={()=>setDetailId(null)}>상세 닫기</button></div><p>{shortProblemId(detail.problemVersion)} · {new Date(detail.createdAt).toLocaleString('ko-KR')}</p>{detail.problemHeld&&<p className="notice">검토 보류된 문제의 기존 제출입니다.</p>}{detail.compileMessage&&<pre>{detail.compileMessage}</pre>}<ExecutionMetrics result={detail}/><pre aria-label="제출 당시 코드">{detail.source}</pre></section>}
 </section>;
}
