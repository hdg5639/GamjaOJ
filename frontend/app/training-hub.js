'use client';
import {useCallback,useEffect,useRef,useState} from 'react';
import SessionPanel from './session-panel';
import FollowupPanel from './followup-panel';
import LearningTracks from './learning-tracks';
import TrainingCourses from './training-courses';
import {AiBudget} from './ai-operations';
export default function TrainingHub({user,api,problem,problems,sessions,onChange,activity,onOpen,onDiagnostic,onGeneration,locked,visible,initialEvaluation}) {
 const [view,setView]=useState('plans'),[toolsHost,setToolsHost]=useState(null),[courseToolsHost,setCourseToolsHost]=useState(null),[resumeEnrollment,setResumeEnrollment]=useState(''),[resolvingEntry,setResolvingEntry]=useState(true);
 const navigation=useRef(0);
 const navigate=next=>{navigation.current++;setView(next);};
 useEffect(()=>{if(!visible)return;setResolvingEntry(true);let stopped=false;const revision=navigation.current;
  api('/api/training-courses/enrollments').then(items=>{if(stopped||revision!==navigation.current||!Array.isArray(items))return;
   const active=sessions.find(s=>s.status==='ACTIVE');
   const current=items.find(e=>e.steps?.some(s=>s.sessionId===active?.id&&s.sessionStatus==='ACTIVE'))||items.find(e=>e.solved<e.steps?.length);
   if(current){setResumeEnrollment(current.enrollmentId);setView('courses');}else if(!initialEvaluation)setView('plans');
  }).catch(()=>{}).finally(()=>{if(!stopped)setResolvingEntry(false);});return()=>{stopped=true;};
 },[visible,user.id]);
 useEffect(()=>{if(initialEvaluation){navigation.current++;setView('plans');}},[initialEvaluation]);
 const showRecords=useCallback(()=>{navigation.current++;setView('records');},[]);
 useEffect(()=>{const show=()=>navigate('plans');window.addEventListener('gamjaoj-curriculum-open',show);return()=>window.removeEventListener('gamjaoj-curriculum-open',show);},[]);
 return <div className="training-hub"><nav className="training-hub-tabs" aria-label="훈련 화면"><button className="secondary" aria-current={view==='courses'?'page':undefined} onClick={()=>navigate('courses')}>훈련 코스</button><button className="secondary" aria-current={view==='plans'?'page':undefined} onClick={()=>navigate('plans')}>학습 계획</button><button className="secondary" aria-current={view==='records'?'page':undefined} onClick={showRecords}>내 훈련 기록</button></nav>
  {view==='courses'&&<TrainingCourses api={api} userId={user.id} visible={visible} sessions={sessions} onSessionsChange={onChange} onOpen={onOpen} onToolsHost={setCourseToolsHost} locked={locked} activity={activity} resumeEnrollment={resumeEnrollment}/>}
  <div hidden={view!=='plans'}><LearningTracks api={api} userId={user.id} visible={visible&&!resolvingEntry&&view==='plans'} initialEvaluation={initialEvaluation} onOpen={onOpen} onGeneration={onGeneration} onDiagnostic={onDiagnostic} locked={locked} sessions={sessions} problems={problems} onToolsHost={setToolsHost} onSessionsChange={onChange}/></div>
  <SessionPanel user={user} api={api} problem={problem} problems={problems} sessions={sessions} onChange={onChange} activity={activity} onOpen={onOpen} onDiagnostic={onDiagnostic} locked={locked} view={view==='records'?'records':'learning'} onRecords={showRecords} controlsHost={view==='plans'?toolsHost:view==='courses'?courseToolsHost:null}/>
  <div hidden={view!=='plans'}><details className="training-extra-practice"><summary>제출 피드백으로 만든 연습 목표</summary><FollowupPanel api={api} onOpen={onOpen} onGeneration={onGeneration} locked={locked}/></details><AiBudget api={api} visible={visible} collapsible/></div>
 </div>;
}
