'use client';

import {useEffect,useState} from 'react';

/** Client-side paging for bounded lists; the page stays valid while polling changes the list. */
export function usePage(items,size) {
  const [page,setPage]=useState(0);
  const pages=Math.max(1,Math.ceil(items.length/size));
  const current=Math.min(page,pages-1);
  useEffect(()=>{if(page!==current)setPage(current);},[page,current]);
  return {visible:items.slice(current*size,(current+1)*size),offset:current*size,page:current,pages,setPage};
}

export default function Pager({paging,label}) {
  const {page,pages,setPage}=paging;
  if(pages<=1)return null;
  return <nav className="pager" aria-label={label}>
    <button type="button" className="secondary" disabled={page===0} onClick={()=>setPage(page-1)}>이전</button>
    <span aria-live="polite">{page+1} / {pages}</span>
    <button type="button" className="secondary" disabled={page>=pages-1} onClick={()=>setPage(page+1)}>다음</button>
  </nav>;
}
