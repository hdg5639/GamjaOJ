'use client';
import { useEffect, useState } from 'react';
export default function ProblemTeaching({version,api}) {
  const [content,setContent]=useState(null),[error,setError]=useState('');
  useEffect(()=>{let stopped=false;api(`/api/problems/${version}/teaching`).then(data=>{if(!stopped)setContent(data);}).catch(e=>{if(!stopped)setError(e.message);});return()=>{stopped=true;};},[version]);
  return <div className="problem-teaching">
    {error&&<p role="alert">{error}</p>}
    {(content?.hints||[]).map((hint,i)=><details key={i}><summary>기본 힌트 {i+1}</summary><p>{hint}</p></details>)}
    {content?.editorial&&<details><summary>해설 보기</summary><p>{content.editorial}</p></details>}
  </div>;
}
