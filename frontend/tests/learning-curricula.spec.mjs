import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
for(const width of [390,820,1710])test(`one click curriculum, learning progress and separate records at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});
 await page.addInitScript(()=>{
  localStorage.setItem('gamjaoj-theme','dark');localStorage.setItem('gamjaoj-appearance-v1',JSON.stringify({dark:{canvas:'#101827',surface:'#18243a',subtle:'#22334d',ink:'#e6eefb',muted:'#a7b8d1',line:'#354760',green:'#568bc9'}}));
 });
 const items=[{id:'i0',itemId:'i0',category:'dp',status:'SKIPPED',skipReason:'NOT_SURE',attempts:0},{id:'i1',itemId:'i1',category:'implementation',status:'PASSED',attempts:1}];
 const session={id:'diagnostic',status:'COMPLETED',bankId:'algo-mix-a-v2',createdAt:'2026-10-03T10:00:00Z',items,current:null};
 const observation={submissionId:'old',tone:'RISK',confidence:'SUPPORTED',nextAction:'PRACTICE',pattern:'입력 범위를 점검하기',recommendation:'누적값의 범위를 먼저 확인하기',interpretation:'범위를 확인해 보세요.',quote:'int sum = 0;'};
 const evaluation={id:'evaluation',status:'COMPLETED',facts:{complete:true,items},interpretation:{summary:'이번 코드에서 확인한 보완점입니다.',uncertainty:'다른 문제의 숙련은 알 수 없습니다.',observations:[observation]},corrections:[]};
 const problems=[{version:'one',title:'합계의 범위',category:'구현',difficulty:'EASY',statement:'두 수의 합을 출력하세요.',submissionsEnabled:true},{version:'two',title:'차근차근 쌓는 점수',category:'동적 계획법',difficulty:'EASY',statement:'점수를 누적하세요.',submissionsEnabled:true}];
 const plans=[{id:'basic',evaluationId:'evaluation',observationIndex:0,sourceKind:'SELF_REPORT',goal:'동적 계획법의 기본 개념을 확인하기',status:'READY',roundNumber:1},{id:'code',evaluationId:'evaluation',observationIndex:0,sourceKind:'CODE_OBSERVATION',goal:'누적값의 범위를 먼저 확인하기',status:'READY',roundNumber:1}];
 let created=false,createKeys=[],createBodies=[],startBodies=[],reflectionBodies=[],paidPosts=0;
 let records=Array.from({length:14},(_,i)=>({id:`past-${i}`,status:'ENDED',problemVersion:'one',goal:`이전 훈련 ${i}`,startedAt:'2026-10-01T10:00:00Z',endedAt:'2026-10-01T10:20:00Z',submissions:2,accepted:1,pending:0}));
 const track=()=>({evaluationId:'evaluation',diagnosticSessionId:'diagnostic',bankId:'algo-mix-a-v2',createdAt:session.createdAt,manualReviewCount:1,steps:plans.map((plan,i)=>({plan:{...plan},category:i===0?'dp':'implementation',basis:plan.sourceKind,problemTitle:plan.problemVersion?problems.find(p=>p.version===plan.problemVersion).title:null,candidate:plan.status==='READY'?{...problems[i===0?1:0]}:null,progress:plan.sessionId?{submissions:1,accepted:1,pending:0,latestVerdict:'AC'}:null}))});
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=problems;
  if(path==='/api/diagnostics')data=[session];
  if(path==='/api/diagnostics/diagnostic')data=session;
  if(path==='/api/diagnostics/diagnostic/evaluations')data=[evaluation];
  if(path==='/api/diagnostic-plans')data=created?plans:[];
  if(path==='/api/diagnostic-plans/options')data={reviewHash:'a'.repeat(64),observation,corrections:[],problems,rules:[]};
  if(path==='/api/learning-curricula'){
   if(req.method()==='POST'){
    createKeys.push(req.headers()['idempotency-key']);createBodies.push(req.postDataJSON());created=true;data={evaluationId:'evaluation',plans,manualReviewCount:1};
    if(createKeys.length===1)return route.abort();
   }else data=created?[track()]:[];
  }
  if(path==='/api/diagnostic-plans/basic/start'){
   startBodies.push(req.postDataJSON());plans[0]={...plans[0],status:'ACTIVE',sessionId:'basic',problemVersion:req.postDataJSON().problemVersion};
   if(!records.some(r=>r.id==='basic'))records=[{id:'basic',status:'ACTIVE',problemVersion:'two',goal:plans[0].goal,startedAt:'2026-10-03T10:00:00Z',submissions:1,accepted:1,pending:0},...records];
   data=plans[0];if(startBodies.length===1)return route.abort();
  }
  if(path==='/api/training-sessions')data=records;
  if(path==='/api/training-sessions/basic')data={session:records[0],entries:[]};
  if(path==='/api/training-sessions/basic/end'){
   plans[0]={...plans[0],status:'TRAINING_ENDED'};records[0]={...records[0],status:'ENDED',note:req.postDataJSON().note,endedAt:'2026-10-03T10:30:00Z'};data=records[0];
  }
  if(path==='/api/diagnostic-plans/basic/reflect'){
   reflectionBodies.push(req.postDataJSON());plans[0]={...plans[0],status:'SELF_REPORTED_UNASSISTED_AC',usedHelp:false,reviewedSubmissionId:'accepted'};data=plans[0];
   if(reflectionBodies.length===1)return route.abort();
  }
  if((path==='/api/ai/tasks'||path.includes('/generate'))&&req.method()==='POST')paidPosts++;
  await route.fulfill({json:data});
 });
 async function report(){await page.getByRole('button',{name:'선택 진단',exact:true}).click();await page.getByRole('button',{name:'지난 진단',exact:true}).click();await page.getByRole('dialog',{name:'지난 진단'}).getByRole('button',{name:'2문항 · 완료'}).click();}
 await page.goto(base);await report();
 const quick=page.getByRole('region',{name:'맞춤 계획 만들기'});
 await quick.getByRole('button',{name:'맞춤 계획 한 번에 만들기'}).click();
 await expect(quick.getByRole('button',{name:'같은 맞춤 계획 요청 다시 확인'})).toBeEnabled();
 await page.reload();await report();
 await quick.getByRole('button',{name:'같은 맞춤 계획 요청 다시 확인'}).click();
 const learning=page.getByRole('region',{name:'나의 학습 계획'}),next=page.getByRole('region',{name:'다음 학습'});
 await expect(learning).toBeVisible();await expect(learning.getByText('0 / 2 목표 훈련 확인 완료',{exact:true})).toBeVisible();
 expect(createKeys).toHaveLength(2);expect(createKeys[1]).toBe(createKeys[0]);expect(createBodies).toEqual([{evaluationId:'evaluation'},{evaluationId:'evaluation'}]);
 await expect(page.locator('.training-record-row')).toHaveCount(0);
 await expect(learning).toContainText('정정 의견이 있는 제안 1개');
 await page.screenshot({path:`/tmp/gamja-curriculum-plans-${width}.png`,fullPage:true});
 await next.getByRole('button',{name:'바로 훈련 시작'}).click();
 await expect(learning.getByRole('button',{name:'같은 학습 요청 다시 확인'})).toBeEnabled();
 await page.reload();await learning.getByRole('button',{name:'같은 학습 요청 다시 확인'}).click();
 await expect(page.getByLabel('풀이할 문제')).toHaveValue('two');await expect.poll(()=>startBodies.length).toBe(2);expect(startBodies).toEqual([{problemVersion:'two'},{problemVersion:'two'}]);
 await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
 await expect(next.getByRole('button',{name:'이어서 학습하기'})).toBeVisible();
 await page.getByRole('button',{name:'훈련 마무리',exact:true}).click();
 const finish=page.getByRole('dialog',{name:'훈련 마무리'});await finish.getByLabel('마무리 메모').fill('다른 입력에서도 확인했어요.');await finish.getByRole('button',{name:'훈련 마치기'}).click();
 await next.getByRole('button',{name:'혼자 해결했어요'}).click();
 await expect(learning.getByRole('button',{name:'같은 학습 요청 다시 확인'})).toBeEnabled();
 await page.reload();await learning.getByRole('button',{name:'같은 학습 요청 다시 확인'}).click();
 await expect(learning.getByText('1 / 2 목표 훈련 확인 완료',{exact:true})).toBeVisible();await expect.poll(()=>reflectionBodies.length).toBe(2);expect(reflectionBodies).toEqual([{usedHelp:false},{usedHelp:false}]);
 await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();
 await expect(learning).toBeHidden();await expect(page.locator('.training-record-row')).toHaveCount(10);
 await page.getByLabel('훈련 기록 검색').fill('동적 계획법');await expect(page.locator('.training-record-row')).toHaveCount(1);
 await page.locator('.training-record-row').first().click();const detail=page.getByRole('dialog',{name:'훈련 상세 기록'});await expect(detail.getByLabel('저장된 마무리 메모')).toHaveText('다른 입력에서도 확인했어요.');await detail.getByRole('button',{name:'닫기',exact:true}).click();
 await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'학습 계획',exact:true}).click();
 await learning.locator('.learning-plan-steps > li').nth(1).getByRole('button',{name:'수동 설정·근거 확인'}).click();
 await learning.getByRole('button',{name:'이 제안으로 학습 계획 준비'}).click();
 await expect(learning.getByLabel('내가 확인한 연습 목표')).toHaveValue('누적값의 범위를 먼저 확인하기');await learning.getByLabel('직접 고를 연습 문제').selectOption('one');
 await expect(learning.getByRole('button',{name:'선택한 문제로 훈련 시작'})).toBeEnabled();
 expect(paidPosts).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await learning.getByRole('button',{name:'진단·수동 계획 만들기'}).click();await expect(page.getByRole('heading',{name:'진단 결과',exact:true})).toBeVisible();
});
test('next goal opens the correct page; generation and held goals keep their gates',async({page})=>{
 await page.setViewportSize({width:820,height:1000});
 const plans=Array.from({length:12},(_,index)=>({id:`p-${index}`,evaluationId:'evaluation',observationIndex:index,sourceKind:'CODE_OBSERVATION',goal:`순서대로 확인할 목표 ${index}`,status:index<8?'SELF_REPORTED_UNASSISTED_AC':index===10?'HELD':'READY',roundNumber:1,...(index===9?{generationId:'generation',generationStatus:'DESIGNING'}:{})}));
 let paid=0;
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/learning-curricula')data=[{evaluationId:'evaluation',diagnosticSessionId:'diagnostic',bankId:'algo-mix-a-v2',createdAt:'2026-10-03T10:00:00Z',steps:plans.map(plan=>({plan,basis:'CODE_OBSERVATION',category:'dp',candidate:null,progress:null}))}];
  if(path==='/api/diagnostic-plans')data=plans;
  if(path==='/api/diagnostic-plans/options')data={reviewHash:'a'.repeat(64),observation:{recommendation:'목표 직접 확인',nextAction:'PRACTICE'},corrections:[],rules:[],problems:[]};
  if(path.endsWith('/generate')&&req.method()==='POST')paid++;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#training');
 const learning=page.getByRole('region',{name:'나의 학습 계획'});
 await expect(learning.locator('.learning-plan-steps > li')).toHaveCount(5);
 await expect(learning.getByText('8 / 12 목표 훈련 확인 완료',{exact:true})).toBeVisible();
 await page.getByRole('region',{name:'다음 학습'}).getByRole('button',{name:'목표·문제 직접 설정'}).click();
 await expect(learning.locator('#learning-manual-p-8')).toBeFocused();
 await expect(learning.getByRole('navigation',{name:'학습 목표 페이지'})).toContainText('2 / 3');
 await learning.getByRole('button',{name:'이 제안으로 학습 계획 준비'}).click();
 await expect(learning.getByLabel('내가 확인한 연습 목표')).toHaveValue('순서대로 확인할 목표 8');
 await expect(learning.locator('.learning-plan-steps > li').filter({hasText:'순서대로 확인할 목표 9'}).getByRole('button',{name:'생성·검증 상세 보기'})).toBeVisible();
 await learning.getByRole('navigation',{name:'학습 목표 페이지'}).getByRole('button',{name:'다음',exact:true}).click();
 await expect(learning.locator('.learning-plan-steps > li').filter({hasText:'순서대로 확인할 목표 10'}).getByRole('button',{name:'수동 설정·근거 확인'})).toBeDisabled();
 expect(paid).toBe(0);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
for(const width of [390,1710])test(`automatic preparation stays in learning and becomes playable at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});
 let phase='DRAFT_READY',automatic=0,manual=0,started=0;
 const plan={id:'auto',evaluationId:'e',observationIndex:0,sourceKind:'SELF_REPORT',goal:'동적 계획법 기초 복습',status:'READY',generationId:'auto',generationStatus:phase};
 const problem={version:'published',title:'하루씩 쌓는 점수',category:'동적 계획법',difficulty:'EASY',statement:'점수를 구하세요.',submissionsEnabled:true};
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/problems')data=phase==='PUBLISHED'?[problem]:[];
  if(path==='/api/learning-curricula')data=[{evaluationId:'e',bankId:'algo-mix-a-v2',createdAt:'2026-10-03T10:00:00Z',steps:[{plan:{...plan,generationStatus:phase},basis:'SELF_REPORT',category:'dp',candidate:phase==='PUBLISHED'?problem:null,preparation:automatic?{status:phase==='PUBLISHED'?'MAPPED':'GENERATING'}:null}]}];
  if(path==='/api/learning-curricula/plans/auto/prepare'){automatic++;data={status:'GENERATING'};}
  if(path==='/api/diagnostic-plans/auto/start'){started++;Object.assign(plan,{status:'ACTIVE',problemVersion:'published',sessionId:'auto'});data=plan;}
  if(path.includes('/generation')&&req.method()==='POST')manual++;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#training');
 const next=page.getByRole('region',{name:'다음 학습'});
 await expect.poll(()=>automatic).toBe(1);
 for(const status of ['DRAFT_READY','BUILD_GENERATING','CHECKED','REVIEW_GENERATING','FINAL_CHECKING']){
  phase=status;await page.evaluate(()=>window.dispatchEvent(new Event('gamjaoj-plan-changed')));
  await expect(next.getByRole('status')).toContainText('자동 생성·검증');
  await expect(next.getByRole('button',{name:'생성·검증 상세 보기'})).toBeVisible();
  await expect(next.getByRole('button',{name:'바로 훈련 시작'})).toHaveCount(0);
 }
 await page.screenshot({path:`/tmp/gamja-auto-generation-${width}.png`,fullPage:true});
 phase='PUBLISHED';await page.evaluate(()=>window.dispatchEvent(new Event('gamjaoj-plan-changed')));
 await expect(next).toContainText('하루씩 쌓는 점수');await next.getByRole('button',{name:'바로 훈련 시작'}).click();
 await expect.poll(()=>started).toBe(1);await expect(page.getByRole('heading',{name:'하루씩 쌓는 점수',exact:true})).toBeVisible();
 expect(manual).toBe(0);expect(automatic).toBe(1);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
test('automatic generation failures explain recovery without starting another paid job',async({page})=>{
 let posts=0;
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/learning-curricula')data=[{evaluationId:'e',bankId:'algo-mix-a-v2',createdAt:'2026-10-03T10:00:00Z',steps:[{plan:{id:'failed',status:'READY',goal:'방문 상태를 구분하기',generationId:'failed',generationStatus:'BUILD_FAILED'},candidate:null,preparation:{status:'FAILED',message:'생성·검증을 통과하지 못했어요.'}}]}];
  if(req.method()==='POST')posts++;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#training');
 const next=page.getByRole('region',{name:'다음 학습'});
 await expect(next).toContainText('문제 준비에 확인이 필요해요.');
 await expect(next).toContainText('생성·검증을 통과하지 못했어요.');
 await expect(next.getByRole('button',{name:'생성·검증 상세 보기'})).toBeVisible();
 await expect(next.getByRole('button',{name:'바로 훈련 시작'})).toHaveCount(0);expect(posts).toBe(0);
});
