'use client';
import SelectControl from './select-control';

import { useEffect, useState } from 'react';
import SiteNotice from './site-notice';
import GrowthSummary from './growth-summary';
import ProblemId,{shortProblemId} from './problem-id';
import ListPagination from './list-pagination';
import {ThinkingGuide,thinkingLayers,thinkingSource} from './thinking-difficulty';
const solveLabels={SOLVED:'해결',ATTEMPTED:'제출했지만 미해결',UNATTEMPTED:'미제출'};
const levels={UNRATED:'겹 미배정',...Object.fromEntries(thinkingLayers.map(([name],i)=>[String(i+1),`${i+1}겹 · ${name}`]))};

export default function ProblemCatalog({ problems, loaded, error, selectedVersion, locked, onChoose, api, onChanged, home=false, onNavigate, activity=0 }) {
  const [query,setQuery]=useState(''),[scope,setScope]=useState('all'),[category,setCategory]=useState(''),[difficulty,setDifficulty]=useState(''),[tag,setTag]=useState(''),[solve,setSolve]=useState('');
  const [editing,setEditing]=useState(null),[saving,setSaving]=useState(false),[saveError,setSaveError]=useState(''),[saved,setSaved]=useState('');
  const [page,setPage]=useState(1),[sort,setSort]=useState(''),[editLayer,setEditLayer]=useState('UNRATED');
  const [filtersOpen,setFiltersOpen]=useState(false),[removing,setRemoving]=useState(null);
  async function remove(p){
    if(saving)return;setSaving(true);setSaveError('');setSaved('');
    try{const result=await api(`/api/problems/${encodeURIComponent(p.version)}`,{method:'DELETE'});
      setSaved(result.outcome==='ARCHIVED'?`${p.title}을(를) 목록에서 내렸어요. 다른 회원이 이미 풀어서 그들의 기록을 위해 문제 자체는 보관돼요.`:`${p.title}을(를) 삭제했어요.`);
      setRemoving(null);window.dispatchEvent(new Event('gamjaoj-problems-changed'));
    }catch(e){setSaveError(e.message);}finally{setSaving(false);}
  }
  useEffect(()=>{ const media=window.matchMedia('(min-width: 801px)'); const sync=()=>setFiltersOpen(media.matches); sync(); media.addEventListener('change',sync); return ()=>media.removeEventListener('change',sync); },[]);
  const filterCount=[category,difficulty,tag,solve,sort].filter(Boolean).length;
  // Held problems leave the shared lists; their owner still sees them under 내가 만든 문제 to review or delete.
  problems=problems.map(p=>({...p,title:p.title?.trim()||`연습 문제 · ${shortProblemId(p.version)}`})).filter(p=>!p.problemHeld||(p.mine&&scope==='mine'));
  const term=query.trim().toLocaleLowerCase();
  const matches=problems.filter(p=>(scope==='mine'?p.mine:scope==='others'?p.shared&&!p.mine&&p.generated:p.shared!==false)
    &&(!category||(p.category||'미분류')===category)&&(!difficulty||(difficulty.startsWith('MIN')?(p.thinking?.layer||0)>=Number(difficulty.slice(3))&&['CURATED_ESTIMATE','MODEL_ESTIMATE'].includes(p.thinking?.source):String(p.thinking?.layer||'UNRATED')===difficulty))&&(!tag||(p.tags||[]).includes(tag))
    &&(!solve||(solve==='UNSOLVED'?['UNATTEMPTED','ATTEMPTED'].includes(p.solveStatus):p.solveStatus===solve))
    &&`${p.title} ${p.version} ${shortProblemId(p.version)} ${p.category||''} ${(p.tags||[]).join(' ')}`.toLocaleLowerCase().includes(term)).sort((a,b)=>sort==='asc'?(a.thinking?.layer??10)-(b.thinking?.layer??10):sort==='desc'?(b.thinking?.layer??0)-(a.thinking?.layer??0):0);
  const pageSize=20,pages=Math.max(1,Math.ceil(matches.length/pageSize)),currentPage=Math.min(page,pages);
  const visible=matches.slice((currentPage-1)*pageSize,currentPage*pageSize);
  useEffect(()=>setPage(1),[query,scope,category,difficulty,tag,solve,sort]);
  useEffect(()=>setPage(p=>Math.min(p,pages)),[pages]);
  function changePage(value){setPage(value);setRemoving(null);requestAnimationFrame(()=>{const list=document.getElementById('catalog-results');list?.scrollIntoView({block:'start'});list?.focus({preventScroll:true});});}
  const categories=[...new Set(problems.map(p=>p.category||'미분류'))].sort();
  const tags=[...new Set(problems.flatMap(p=>p.tags||[]))].sort();
  async function save(event){
    event.preventDefault();if(saving)return;setSaving(true);setSaveError('');setSaved('');
    const fields=Object.fromEntries(new FormData(event.currentTarget));
    try{const value=await api(`/api/problems/${encodeURIComponent(editing.version)}/catalog-settings`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({shared:fields.shared==='on',category:fields.category.trim(),tags:fields.tags.split(',').map(t=>t.trim()).filter(Boolean),difficulty:editing.difficulty||'UNRATED',...(fields.thinkingLayer==='UNRATED'?{clearThinking:true}:{thinking:{layer:Number(fields.thinkingLayer),insight:Number(fields.insight),implementation:Number(fields.implementation),edgeCases:Number(fields.edgeCases),rationale:fields.rationale.trim()}})})});
      onChanged(value);setSaved(`${value.title}의 공개·분류 설정을 저장했어요.`);setEditing(null);
    }catch(e){setSaveError(e.message);}finally{setSaving(false);}
  }
  return <section className="problem-catalog" aria-label="문제 목록">
    {home&&api&&<GrowthSummary api={api} activity={activity} onNavigate={onNavigate} onTarget={layer=>{setScope('all');setCategory('');setTag('');setQuery('');setDifficulty(`MIN${layer}`);setSolve('UNSOLVED');setSort('asc');setFiltersOpen(true);requestAnimationFrame(()=>document.getElementById('catalog-results')?.scrollIntoView({block:'start'}));}}/>}
    <div className="tabs catalog-scopes" role="group" aria-label="문제 공개 범위">
      {[['all','전체 공개 문제'],['mine','내가 만든 문제'],['others','다른 사람의 문제']].map(([id,label])=><button key={id} aria-pressed={scope===id} className={scope===id?'selected':''} onClick={()=>setScope(id)}>{label}</button>)}
    </div>
    <div className="catalog-toolbar">
      <label>문제 검색<input type="search" value={query} onChange={e=>setQuery(e.target.value)} placeholder="제목, 문제 ID 또는 태그" autoComplete="off" /></label>
      <button className="secondary catalog-reset" disabled={!(query||category||difficulty||tag||solve||sort)} onClick={()=>{setQuery('');setCategory('');setDifficulty('');setTag('');setSolve('');setSort('');}}>검색 초기화</button>
      <button className="secondary filter-toggle" aria-expanded={filtersOpen} aria-controls="catalog-filters" onClick={()=>setFiltersOpen(!filtersOpen)}>필터{filterCount?` · ${filterCount}`:''} <span aria-hidden="true">{filtersOpen?'−':'+'}</span></button>
    </div>
    <div id="catalog-filters" className="catalog-filters" hidden={!filtersOpen}>
      <label>분야<SelectControl aria-label="분야" value={category} onChange={e=>setCategory(e.target.value)}><option value="">모든 분야</option>{categories.map(c=><option key={c}>{c}</option>)}</SelectControl></label>
      <label>난이도<SelectControl aria-label="난이도" value={difficulty} onChange={e=>setDifficulty(e.target.value)}><option value="">모든 난이도</option>{difficulty.startsWith('MIN')&&<option value={difficulty}>{difficulty.slice(3)}겹 이상 · 다음 성장 목표</option>}{Object.entries(levels).map(([id,label])=><option key={id} value={id}>{label}</option>)}</SelectControl></label>
      <label>정렬<SelectControl aria-label="난도 정렬" value={sort} onChange={e=>setSort(e.target.value)}><option value="">기본 순서</option><option value="asc">낮은 겹부터</option><option value="desc">높은 겹부터</option></SelectControl></label>
      <label>태그<SelectControl aria-label="태그" value={tag} onChange={e=>setTag(e.target.value)}><option value="">모든 태그</option>{tags.map(t=><option key={t}>{t}</option>)}</SelectControl></label>
      <label>내 풀이 상태<SelectControl aria-label="내 풀이 상태" value={solve} onChange={e=>setSolve(e.target.value)}><option value="">모든 상태</option><option value="UNSOLVED">미해결 전체</option>{Object.entries(solveLabels).map(([id,label])=><option key={id} value={id}>{label}</option>)}</SelectControl></label>
    </div>
    <ThinkingGuide/>
    {saved&&<p className="notice success" role="status">{saved}</p>}
    {editing&&<form className="catalog-edit" aria-label="공개·분류 설정" onSubmit={save}>
      <h3>{editing.title} · 공개·분류 설정</h3>
      <p className="muted">공개하면 문제 본문·예제·힌트·해설을 다른 회원도 이용할 수 있어요. 내 제출 코드와 출제 요청은 공유하지 않습니다.</p>
      <label className="check-row"><input type="checkbox" name="shared" defaultChecked={editing.shared}/>다른 회원에게 공개</label>
      <label>분류 이름<input name="category" required maxLength={80} defaultValue={editing.category||'미분류'}/></label>
      <label>분류 태그<input name="tags" defaultValue={(editing.tags||[]).join(', ')} placeholder="쉼표로 구분 · 최대 6개" maxLength={490}/></label>
      <label>생각의 겹<SelectControl aria-label="예상 난이도" name="thinkingLayer" value={editLayer} onChange={e=>setEditLayer(e.target.value)}>{Object.entries(levels).map(([id,label])=><option key={id} value={id}>{label}</option>)}</SelectControl></label>
      {editLayer!=='UNRATED'&&<><div className="thinking-edit-axes">{[['발상','insight'],['구현','implementation'],['경계','edgeCases']].map(([label,key])=><label key={key}>{label}<SelectControl name={key} defaultValue={editing.thinking?.[key]||3}>{[1,2,3,4,5].map(n=><option key={n} value={n}>{n} / 5</option>)}</SelectControl></label>)}</div>
      <label>배정 근거<textarea name="rationale" rows={3} required maxLength={300} defaultValue={editing.thinking?.rationale||''} placeholder="규칙·제약·생각의 부담을 설명해 주세요. 정답 기법이나 해설은 적지 마세요."/></label><p className="muted">근거와 세 축도 공개돼요. 내 자신감과는 별개인 출제자 추정입니다.</p></>}
      {saveError&&<p role="alert" className="notice error">{saveError}</p>}
      <div className="catalog-actions"><button className="primary" disabled={saving}>{saving?'저장 중…':'설정 저장'}</button><button type="button" className="secondary" disabled={saving} onClick={()=>setEditing(null)}>닫기</button></div>
    </form>}
    {!loaded?<div><p role={error?'alert':'status'}>{error||'문제 목록을 불러오고 있어요…'}</p>{error&&<button className="secondary" onClick={()=>window.location.reload()}>다시 불러오기</button>}</div>:<>
      <p id="catalog-results" tabIndex={-1} className="muted catalog-results" role="status">{term||category||difficulty||tag||solve?`검색 결과 ${matches.length}개 · 전체 ${problems.length}개`:`${scope==='mine'?'내가 만든 문제':scope==='others'?'다른 사람의 문제':'전체 공개 문제'} ${matches.length}개`}{matches.length>0&&` · ${(currentPage-1)*pageSize+1}–${Math.min(currentPage*pageSize,matches.length)}번째`}</p>
      {error&&<p className="notice error" role="alert">{error}</p>}
      {locked&&<p className="notice">제출 접수 확인 또는 파일 처리가 끝나면 문제를 선택할 수 있어요.</p>}
      {!matches.length?<p className="catalog-empty">{!problems.length?'현재 풀이할 수 있는 문제가 없어요.':scope==='mine'&&!term&&!category&&!difficulty&&!tag&&!solve?'아직 내가 만든 문제가 없어요. 문제 생성에서 나만의 연습 문제를 만들어 보세요.':'일치하는 문제가 없어요. 제목이나 문제 ID를 바꾸거나 필터를 지워 주세요.'}</p>:
        <ul className="catalog-list">{visible.map(p=><li key={p.version}>
          <div className="catalog-level thinking-level" data-layer={p.thinking?.layer||0}><strong>{p.thinking?`${p.thinking.layer}겹`:'미배정'}</strong><span>{p.thinking?thinkingLayers[p.thinking.layer-1]?.[0]:'검토 전'}</span><small>{p.thinking?thinkingSource(p):''}</small></div>
          <div className="catalog-problem"><h2>{p.title}</h2><ProblemId version={p.version}/>
            <p className="catalog-tags"><span>{p.category||'미분류'}</span>{(p.tags||[]).filter(t=>t!==p.category).map(t=><span key={t}>#{t}</span>)}</p>
            <p className="catalog-progress"><strong data-solve={p.solveStatus}>{solveLabels[p.solveStatus]||'풀이 기록 확인 전'}</strong>{p.pendingSubmissions>0&&<span> · 채점 중 {p.pendingSubmissions}건</span>}</p>
            {p.mine&&<span className="catalog-note">내가 만든 문제 · {p.shared?'공개':'비공개'}</span>}
            {p.problemHeld&&<span className="catalog-note catalog-held">검토 보류 중{p.reviewReason?` · ${p.reviewReason}`:''} · 새 풀이는 막혀 있고 삭제할 수 있어요</span>}
            {!p.submissionsEnabled&&!p.problemHeld&&<span className="catalog-note">채점 준비 중 · 코드 작성 가능</span>}</div>
          <div className="catalog-actions">{p.mine&&api&&!p.problemHeld&&<button className="secondary" disabled={saving} onClick={()=>{setEditing(p);setEditLayer(String(p.thinking?.layer||'UNRATED'));setSaveError('');setSaved('');requestAnimationFrame(()=>document.querySelector('.catalog-edit input')?.focus());}}>공개·분류 설정</button>}
          {p.mine&&api&&<button className="secondary" disabled={saving} onClick={()=>{setRemoving(p.version);setSaved('');setSaveError('');}}>삭제</button>}
          <button className="secondary" disabled={locked} aria-label={`${p.title} · ${p.version} ${selectedVersion===p.version?'이어서 풀기':'풀기'}`} onClick={()=>onChoose(p.version)}>{p.problemHeld?'기록 보기':selectedVersion===p.version?'이어서 풀기':'풀기'}</button></div>
          {removing===p.version&&<div className="notice catalog-remove" role="group" aria-label={`${p.title} 삭제 확인`}>
            <p>이 문제를 삭제할까요? 이 문제에 대한 내 제출·훈련 기록도 함께 삭제되고 되돌릴 수 없어요. 다른 회원이 이미 푼 문제라면 목록에서만 내려가고 그들의 기록은 유지돼요.</p>
            {saveError&&!editing&&<p role="alert" className="notice error">{saveError}</p>}
            <button className="danger" disabled={saving} onClick={()=>remove(p)}>삭제하기</button> <button className="secondary" disabled={saving} onClick={()=>setRemoving(null)}>취소</button></div>}
        </li>)}</ul>}
      <ListPagination page={currentPage} pages={pages} onChange={changePage}/>
    </>}
    {home&&<SiteNotice/>}
  </section>;
}
