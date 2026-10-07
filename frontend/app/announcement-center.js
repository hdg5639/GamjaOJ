'use client';
import {useEffect,useState} from 'react';
import Modal from './modal';
import {announcements} from './announcements';

const filters=['전체','공지','새 기능','업데이트'];
function loadRead(key){
 try{
  const value=JSON.parse(localStorage.getItem(key)||'[]');
  return Array.isArray(value)?value.filter(id=>announcements.some(item=>item.id===id)):[];
 }catch{return [];}
}
export default function AnnouncementCenter({accountId='anonymous'}){
 const key='gamjaoj-notice-read-v1:'+accountId;
 const [open,setOpen]=useState(false),[read,setRead]=useState(null),[filter,setFilter]=useState('전체'),[expanded,setExpanded]=useState(null);
 useEffect(()=>{
  const update=()=>setRead(loadRead(key));
  update();
  const sync=event=>{if(event.key===key||event.key===null)update();};
  window.addEventListener('storage',sync);
  return ()=>window.removeEventListener('storage',sync);
 },[key]);
 const unread=read===null?0:announcements.filter(item=>!read.includes(item.id)).length;
 function mark(ids){
  const next=[...new Set([...loadRead(key),...(read||[]),...ids])];
  setRead(next);try{localStorage.setItem(key,JSON.stringify(next));}catch{}
 }
 const visible=announcements.filter(item=>filter==='전체'||item.kind===filter);
 return <>
  <button type="button" className="secondary notice-trigger" aria-label={'공지·업데이트'+(unread?' · 읽지 않은 글 '+unread+'개':'')} aria-haspopup="dialog" onClick={()=>setOpen(true)} title="공지·업데이트">
   <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg>
   {unread>0&&<span className="notice-unread-dot" aria-hidden="true"/>}
  </button>
  <Modal open={open} title="공지·업데이트" onClose={()=>setOpen(false)} className="announcement-dialog" description="새 소식과 서비스 이용 안내를 확인해요.">
   <div className="announcement-toolbar">
    <div className="announcement-filters" role="group" aria-label="소식 종류">{filters.map(value=><button type="button" key={value} aria-pressed={filter===value} className={filter===value?'selected':''} onClick={()=>setFilter(value)}>{value}</button>)}</div>
    <button type="button" className="secondary notice-read-all" disabled={!unread} onClick={()=>mark(announcements.map(item=>item.id))}>모두 읽음</button>
   </div>
   <p className="announcement-read-state" role="status">{unread?'읽지 않은 소식 '+unread+'개':'새 소식을 모두 확인했어요.'}</p>
   <ul className="announcement-list">{visible.map(item=>{
    const isOpen=expanded===item.id,isUnread=read!==null&&!read.includes(item.id);
    return <li key={item.id}>
     <button type="button" className="announcement-summary" aria-expanded={isOpen} aria-controls={'notice-body-'+item.id} onClick={()=>{setExpanded(isOpen?null:item.id);if(!isOpen)mark([item.id]);}}>
      <span className="announcement-meta"><span>{item.kind}{item.pinned?' · 이용 안내':''}</span><time dateTime={item.date}>{item.date.replaceAll('-','.')}</time>{isUnread&&<span className="announcement-new">새 글</span>}</span>
      <span className="announcement-title">{item.title}<span className="announcement-chevron" aria-hidden="true">{isOpen?'−':'+'}</span></span>
      <span className="announcement-excerpt">{item.summary}</span>
     </button>
     <div id={'notice-body-'+item.id} className="announcement-detail" hidden={!isOpen}>{item.paragraphs.map((text,i)=><p key={i}>{text}</p>)}</div>
    </li>;
   })}</ul>
   <p className="announcement-footnote">읽음 상태는 이 브라우저에 저장돼요.</p>
  </Modal>
 </>;
}
