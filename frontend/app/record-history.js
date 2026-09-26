'use client';

import {recordLanguageLabel} from './languages';
import {useState} from 'react';

export default function RecordHistory({title,items,selectedId,open,onToggle,onSelect,label}) {
  const [limit,setLimit]=useState(10);
  return <details className="record-history history" open={open} onToggle={event=>onToggle(event.currentTarget.open)}>
    <summary>{title} <span className="muted">({items.length})</span></summary>
    {!items.length&&<p className="muted">아직 기록이 없어요.</p>}
    {items.length>0&&<div className="record-list"><ul>{items.slice(0,limit).map(item=><li key={item.id}>
      <button type="button" aria-pressed={selectedId===item.id} onClick={()=>onSelect(item.id)}>
        <span>{item.problemVersion} · {recordLanguageLabel(item)}<small>{new Date(item.createdAt).toLocaleString('ko-KR')}</small></span>
        <span className={`verdict ${item.verdict||''}`}>{label(item)}</span>
      </button>
    </li>)}</ul>{limit<items.length&&<button type="button" className="secondary" onClick={()=>setLimit(value=>value+10)}>기록 10개 더 보기</button>}</div>}
  </details>;
}
