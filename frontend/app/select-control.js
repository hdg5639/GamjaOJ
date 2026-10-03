'use client';

/** One selection surface; native semantics preserve labels, forms and keyboard access. */
export default function SelectControl({children,className='',...props}){
 return <span className={`select-control${props.disabled?' is-disabled':''}`}>
  <select {...props} className={className}>{children}</select>
  <svg className="select-chevron" width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false"><path d="m6 9 6 6 6-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg>
 </span>;
}
