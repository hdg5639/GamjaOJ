export default function ListPagination({page,pages,onChange,label='문제 목록 페이지',disabled=false}) {
 if(pages<=1)return null;
 const start=Math.max(1,Math.min(page-2,pages-4)),end=Math.min(pages,start+4);
 const numbers=Array.from({length:end-start+1},(_,index)=>start+index);
 return <nav className="list-pagination" aria-label={label}>
  <button type="button" className="secondary" disabled={disabled||page===1} onClick={()=>onChange(page-1)}>이전</button>
  <div className="pagination-numbers">
   {start>1&&<><button type="button" className="secondary" disabled={disabled} onClick={()=>onChange(1)} aria-label="1페이지">1</button>{start>2&&<span aria-hidden="true">…</span>}</>}
   {numbers.map(number=><button type="button" key={number} className="secondary" aria-label={`${number}페이지`} aria-current={number===page?'page':undefined} disabled={disabled} onClick={()=>onChange(number)}>{number}</button>)}
   {end<pages&&<>{end<pages-1&&<span aria-hidden="true">…</span>}<button type="button" className="secondary" disabled={disabled} onClick={()=>onChange(pages)} aria-label={`${pages}페이지`}>{pages}</button></>}
  </div>
  <span className="pagination-position">{page} / {pages}</span>
  <button type="button" className="secondary" disabled={disabled||page===pages} onClick={()=>onChange(page+1)}>다음</button>
 </nav>;
}
