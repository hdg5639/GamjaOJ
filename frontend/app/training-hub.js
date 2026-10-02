'use client';
import {useCallback,useEffect,useState} from 'react';
import SessionPanel from './session-panel';
import FollowupPanel from './followup-panel';
import LearningTracks from './learning-tracks';
import {AiBudget} from './ai-operations';
export default function TrainingHub({user,api,problem,problems,sessions,onChange,activity,onOpen,onDiagnostic,onGeneration,locked,visible,initialEvaluation}) {
 const [view,setView]=useState('plans'),[toolsHost,setToolsHost]=useState(null);
 useEffect(()=>{if(initialEvaluation)setView('plans');},[initialEvaluation]);
 const showRecords=useCallback(()=>setView('records'),[]);
 useEffect(()=>{const show=()=>setView('plans');window.addEventListener('gamjaoj-curriculum-open',show);return()=>window.removeEventListener('gamjaoj-curriculum-open',show);},[]);
 return <div className="training-hub"><nav className="training-hub-tabs" aria-label="훈련 화면"><button className="secondary" aria-current={view==='plans'?'page':undefined} onClick={()=>setView('plans')}>학습 계획</button><button className="secondary" aria-current={view==='records'?'page':undefined} onClick={showRecords}>내 훈련 기록</button></nav>
  <div hidden={view!=='plans'}><LearningTracks api={api} userId={user.id} visible={visible&&view==='plans'} initialEvaluation={initialEvaluation} onOpen={onOpen} onGeneration={onGeneration} onDiagnostic={onDiagnostic} locked={locked} sessions={sessions} problems={problems} onToolsHost={setToolsHost} onSessionsChange={onChange}/></div>
  <SessionPanel user={user} api={api} problem={problem} problems={problems} sessions={sessions} onChange={onChange} activity={activity} onOpen={onOpen} onDiagnostic={onDiagnostic} locked={locked} view={view==='records'?'records':'learning'} onRecords={showRecords} controlsHost={view==='plans'?toolsHost:null}/>
  <div hidden={view!=='plans'}><details className="training-extra-practice"><summary>제출 피드백으로 만든 연습 목표</summary><FollowupPanel api={api} onOpen={onOpen} onGeneration={onGeneration} locked={locked}/></details><AiBudget api={api} visible={visible} collapsible/></div>
 </div>;
}
