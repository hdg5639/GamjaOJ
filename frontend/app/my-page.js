'use client';
import {useEffect,useRef,useState} from 'react';
import ThinkingDifficulty from './thinking-difficulty';
import GrowthSummary from './growth-summary';
import ProblemId,{shortProblemId} from './problem-id';
import ListPagination from './list-pagination';
import ExecutionMetrics from './execution-metrics';
import {recordLanguageLabel} from './languages';
import {verdictText,verdictHelp} from './verdicts';
import LearningActivity from './learning-activity';
import Modal from './modal';
import AiFeedback from './ai-feedback';
import {confidenceLabels} from './problem-reflection';

export default function MyPage({api,user,problems,onChoose,onDiagnostic,activity=0}) {
 const pageSize=20;
 const [detailError,setDetailError]=useState(''),[detailRefresh,setDetailRefresh]=useState(0);
 const moving=useRef(false);
 const reviewRequest=useRef(0);
 const [learningRefresh,setLearningRefresh]=useState(0),[reviewBusy,setReviewBusy]=useState(null);
 const [tab,setTab]=useState('problems'),[page,setPage]=useState(0),[summary,setSummary]=useState(null),[items,setItems]=useState([]),[total,setTotal]=useState(0),[detail,setDetail]=useState(null),[detailId,setDetailId]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[refresh,setRefresh]=useState(0);
 useEffect(()=>{let live=true;reviewRequest.current++;setReviewBusy(null);setBusy(true);setError('');setItems([]);setDetailId(null);setDetail(null);
  Promise.all([api('/api/my/summary'),api(tab==='problems'?`/api/my/problems?page=${page}`:`/api/submissions?page=${page}&size=${pageSize}`)]).then(([stats,data])=>{if(live){const count=tab==='problems'?data.total:stats.submitted,last=Math.max(0,Math.ceil(count/pageSize)-1);setSummary(stats);setTotal(count);if(page>last){setPage(last);return;}setItems(tab==='problems'?data.items:data);}}).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setBusy(false);});return()=>{live=false;};
 },[tab,page,refresh,user.id,activity]);
 useEffect(()=>{let live=true;setDetail(null);setDetailError('');if(detailId)api(`/api/submissions/${detailId}`).then(value=>{if(live)setDetail(value);}).catch(e=>{if(live)setDetailError(e.message);});return()=>{live=false;};},[detailId,user.id,detailRefresh]);

 const pages=Math.max(1,Math.ceil(total/pageSize));
 function changePage(value){moving.current=true;setPage(value-1);}
 async function openReview(version){const token=++reviewRequest.current;setReviewBusy(version);setError('');try{const reflection=await api(`/api/my/reflections?problemVersion=${encodeURIComponent(version)}`);if(token===reviewRequest.current)setDetailId(reflection.latestAcceptedSubmissionId);}catch(e){if(token===reviewRequest.current)setError(e.message);}finally{if(token===reviewRequest.current)setReviewBusy(null);}}
 function reflectionSaved(){const token=reviewRequest.current;setLearningRefresh(x=>x+1);api.clear?.();api(`/api/my/problems?page=${page}`).then(data=>{if(token===reviewRequest.current&&tab==='problems')setItems(data.items);}).catch(e=>{if(token===reviewRequest.current)setError(e.message);});}
 useEffect(()=>{if(!busy&&moving.current){moving.current=false;const results=document.getElementById('my-records-heading');results?.scrollIntoView({block:'start'});results?.focus({preventScroll:true});}},[busy,items]);
 return <section className="my-activity" aria-label="마이페이지">
  <div className="my-activity-heading"><div><h2>{user.nickname}님의 풀이 기록</h2><p className="muted">정식 제출을 기준으로 모았어요. 직접 실행은 기록에 포함하지 않아요.</p></div><button className="secondary" disabled={busy} onClick={()=>{api.clear?.();setRefresh(x=>x+1);}}>기록 새로고침</button></div>
  <LearningActivity api={api} userId={user.id} activity={activity} refresh={refresh+learningRefresh} onChoose={onChoose}
   growth={<GrowthSummary api={api} activity={activity+refresh} onNavigate={screen=>{if(screen==='diagnostic')onDiagnostic();else window.location.hash=screen;}}/>}
   stats={summary&&<dl className="my-stats"><div><dt>정답을 맞힌 문제</dt><dd>{summary.solvedProblems}</dd></div><div><dt>풀어본 문제</dt><dd>{summary.attemptedProblems}</dd></div><div><dt>정식 제출</dt><dd>{summary.submitted}</dd></div></dl>}/>
  <section className="my-record-section" aria-label="문제와 제출 기록">
   <nav className="my-tabs" aria-label="내 기록 종류">{[['problems','풀어본 문제'],['submissions','전체 제출']].map(([key,label])=><button key={key} aria-pressed={tab===key} onClick={()=>{setTab(key);setPage(0);}}>{label}<span aria-hidden="true">{key==='problems'?summary?.attemptedProblems??'—':summary?.submitted??'—'}</span></button>)}</nav>
   <div className="my-record-toolbar"><p id="my-records-heading" tabIndex={-1} className="muted" role="status">{busy?'기록 확인 중…':`${tab==='problems'?'풀어본 문제':'전체 제출'} ${total}개${total>0?` · ${page*pageSize+1}–${Math.min((page+1)*pageSize,total)}번째`:''}`}</p><ListPagination page={page+1} pages={pages} onChange={changePage} label="상단 기록 페이지" disabled={busy}/></div>
   {error&&<p role="alert" className="notice error">{error}</p>}
   {busy&&<p role="status" className="my-record-empty">기록을 불러오는 중…</p>}
   {!busy&&!error&&!items.length&&<div className="my-record-empty"><strong>{tab==='problems'?'아직 풀어본 문제가 없어요.':'아직 제출한 코드가 없어요.'}</strong><p className="muted">문제를 풀고 정식 제출하면 여기에 기록이 쌓여요.</p></div>}
   {!!items.length&&<div className={`my-column-head ${tab}`} aria-hidden="true">{(tab==='problems'?['문제','풀이 상태','내 평가','최근 제출','다음 행동']:['문제','채점 결과','언어','제출 시각','']).map((label,i)=><span key={i}>{label}</span>)}</div>}
   <ul className={`my-records ${tab}`} aria-label={tab==='problems'?'풀어본 문제 목록':'전체 제출 목록'}>{items.map(item=>tab==='problems'?<li key={item.version} className="my-problem-row">
    <div className="record-problem"><strong>{item.title}</strong><small>{item.category||'분야 미분류'}{!item.diagnostic&&<> · <ThinkingDifficulty compact problem={problems.find(p=>p.version===item.version)}/></>}{item.diagnostic?' · 진단':''}</small><small className="record-id"><ProblemId version={item.version}/></small></div>
    <div className="record-state"><span className={`record-status ${item.held?'held':item.accepted>0?'solved':'attempted'}`}>{item.held?'검토 보류':item.accepted>0?'정답':'도전 중'}</span><small>제출 {item.attempts}회</small></div>
    <div className="record-assessment">{item.confidence&&!item.held?<span className="record-confidence">{confidenceLabels[item.confidence]}</span>:<span className="record-unrated">{item.held?'평가 보류':item.diagnostic?'진단 결과에서 확인':item.accepted>0?'아직 평가하지 않았어요':'정답 후 돌아봐요'}</span>}{item.reflectionNote&&!item.held&&<details className="record-memo"><summary>메모 보기</summary><p tabIndex={0}>{item.reflectionNote}</p></details>}</div>
    <time className="record-date" dateTime={item.lastSubmitted}>{recordDate(item.lastSubmitted)}</time>
    <div className="my-record-actions">{!item.held&&problems.some(p=>p.version===item.version)&&<button className="secondary record-practice" onClick={()=>onChoose(item.version)}>문제 풀기</button>}{!item.held&&!item.diagnostic&&item.accepted>0&&<button className="record-review" disabled={!!reviewBusy} onClick={()=>openReview(item.version)} aria-label={`${item.title} 풀이 돌아보기`}>{reviewBusy===item.version?'불러오는 중…':'돌아보기'}</button>}{item.diagnostic&&<button className="secondary" onClick={onDiagnostic}>진단으로</button>}</div>
   </li>:<li key={item.id} className="my-submission-row"><button className="my-submission" aria-pressed={detailId===item.id} aria-haspopup="dialog" onClick={()=>setDetailId(item.id)}>
    <span className="record-problem"><strong>{problems.find(p=>p.version===item.problemVersion)?.title||shortProblemId(item.problemVersion)}</strong><small className="record-id">{shortProblemId(item.problemVersion)}</small></span>
    <span className="record-state"><span className={`record-status verdict ${item.verdict||'pending'}`} title={verdictHelp[item.verdict]}>{verdictText(item.verdict)|| (item.status==='RUNNING'?'채점 중':'대기')}</span>{item.problemHeld&&<small>검토 보류된 문제</small>}</span>
    <span className="record-language">{recordLanguageLabel(item)}</span><time className="record-date" dateTime={item.createdAt}>{recordDate(item.createdAt)}<small>{recordTime(item.createdAt)}</small></time><span className="record-open" aria-hidden="true">코드 보기 <span>↗</span></span>
   </button></li>)}</ul>
   <ListPagination page={page+1} pages={pages} onChange={changePage} label="기록 페이지" disabled={busy}/>
  </section>
  <Modal open={!!detailId} title="제출 상세" className="my-record-dialog" wide onClose={()=>setDetailId(null)}>
   {detailError?<div className="my-record-empty"><p role="alert" className="notice error">{detailError}</p><button className="secondary" onClick={()=>setDetailRefresh(x=>x+1)}>다시 불러오기</button></div>:!detail?<p role="status" className="my-record-empty">제출 코드를 불러오는 중…</p>:<section id="my-submission-detail" className="my-submission-detail" aria-label="제출 내용">
    <div className="submission-detail-context"><strong>{problems.find(p=>p.version===detail.problemVersion)?.title||shortProblemId(detail.problemVersion)}</strong><span className={`record-status verdict ${detail.verdict||'pending'}`}>{verdictText(detail.verdict)||'채점 중'}</span><span>{recordLanguageLabel(detail)}</span><time dateTime={detail.createdAt}>{recordDate(detail.createdAt)} {recordTime(detail.createdAt)}</time></div>
    {detail.problemHeld&&<p className="notice">검토 보류된 문제의 기존 제출입니다.</p>}
    <ExecutionMetrics result={detail}/><div className="submission-detail-layout"><section className="submission-code"><h4>제출 당시 코드</h4>{detail.compileMessage&&<pre className="notice error">{detail.compileMessage}</pre>}<pre tabIndex={0} aria-label="제출 당시 코드">{detail.source}</pre></section><div className="submission-reflection">{!detail.diagnosticItemId&&<AiFeedback key={detail.id} submission={detail} api={api} review onReflectionSaved={reflectionSaved}/>}</div></div>
   </section>}
  </Modal>
 </section>;
}

function recordDate(value){return value?new Date(value).toLocaleDateString('ko-KR',{year:'2-digit',month:'2-digit',day:'2-digit'}):'—';}
function recordTime(value){return value?new Date(value).toLocaleTimeString('ko-KR',{hour:'2-digit',minute:'2-digit',hour12:false}):'';}
