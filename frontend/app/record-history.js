'use client';

import {recordLanguageLabel} from './languages';
import Pager,{usePage} from './pager';
import {verdictHelp} from './verdicts';

export default function RecordHistory({title,items,selectedId,open,onToggle,onSelect,label}) {
  const paging=usePage(items,10);
  return <details className="record-history history" open={open} onToggle={event=>onToggle(event.currentTarget.open)}>
    <summary>{title} <span className="muted">({items.length})</span></summary>
    {!items.length&&<p className="muted">아직 기록이 없어요.</p>}
    {items.length>0&&<div className="record-list"><ul>{paging.visible.map(item=><li key={item.id}>
      <button type="button" aria-pressed={selectedId===item.id} onClick={()=>onSelect(item.id)}>
        <span>{item.problemVersion} · {recordLanguageLabel(item)}<small>{new Date(item.createdAt).toLocaleString('ko-KR')}</small></span>
        <span className={`verdict ${item.verdict||''}`} title={verdictHelp[item.verdict]}>{label(item)}</span>
      </button>
    </li>)}</ul><Pager paging={paging} label={title+' 페이지'}/></div>}
  </details>;
}
