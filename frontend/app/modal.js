'use client';
import {useEffect,useRef} from 'react';
/** Native modal dialog: Esc and the close button call onClose; focus returns to the opener. */
export default function Modal({open,title,onClose,children,wide=false,className='',description}) {
  const ref=useRef(null);
  useEffect(()=>{const d=ref.current;if(!d)return;if(open&&!d.open)d.showModal();if(!open&&d.open)d.close();},[open]);
  return <dialog ref={ref} className={`modal${wide?' wide':''} ${className}`} aria-label={title} onCancel={e=>{e.preventDefault();onClose();}}
    onClick={e=>{if(e.target===ref.current)onClose();}}>
    {open&&<div className="modal-body">
      <div className="modal-head">{description?<div><h3>{title}</h3><p className="modal-description">{description}</p></div>:<h3>{title}</h3>}<button type="button" className="secondary" onClick={onClose}>닫기</button></div>
      {children}
    </div>}
  </dialog>;
}
