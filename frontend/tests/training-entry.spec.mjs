import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
const problem={version:'one',title:'진행 중인 코스 문제',category:'구현',difficulty:'EASY',statement:'문제를 풀어요.',submissionsEnabled:true};
const active={id:'active',status:'ACTIVE',problemVersion:'one',goal:'차례대로 구현하기',startedAt:'2026-10-06T10:00:00Z',submissions:1,accepted:0,pending:0};
function enrollment(id,title,sessionId=null){return {enrollmentId:id,course:{id,revision:1,title,summary:'단계별 훈련',stages:[{title:'구현',versions:['one']}]},solved:0,steps:[{position:0,version:'one',title:problem.title,category:'구현',goal:active.goal,stage:'구현',sessionId,sessionStatus:sessionId?'ACTIVE':null,available:true,solved:false}]};}
for(const width of [390,1440])test(`enter and reenter active course with finish controls at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.addInitScript(()=>localStorage.setItem('gamjaoj-course-selected-learner','other'));
 let sessions=[active];let ends=0;
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/problems')data=[problem];if(path==='/api/training-sessions')data=sessions;
  if(path==='/api/training-courses/enrollments')data=[enrollment('other','다른 코스'),enrollment('current','진행 중인 코스','active')];
  if(path==='/api/training-sessions/active/end'){ends++;sessions=[{...active,status:'ENDED'}];data=sessions[0];}
  if(path==='/api/training-sessions/active')data={session:sessions[0],entries:[]};
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#training');const nav=page.getByRole('navigation',{name:'훈련 화면'}),course=page.getByRole('region',{name:'훈련 코스',exact:true});
 await expect(nav.getByRole('button',{name:'훈련 코스',exact:true})).toHaveAttribute('aria-current','page');
 await expect(course.getByRole('heading',{name:'진행 중인 코스',exact:true})).toBeVisible();
 await expect(course.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();
 await nav.getByRole('button',{name:'학습 계획',exact:true}).click();await expect(nav.getByRole('button',{name:'학습 계획',exact:true})).toHaveAttribute('aria-current','page');
 await page.getByRole('button',{name:'문제 탐색',exact:true}).click();await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
 await expect(course.getByRole('heading',{name:'진행 중인 코스',exact:true})).toBeVisible();
 await course.getByRole('button',{name:'훈련 마무리',exact:true}).click();const modal=page.getByRole('dialog',{name:'훈련 마무리',exact:true});await modal.getByRole('button',{name:'훈련 마치기',exact:true}).click();await expect.poll(()=>ends).toBe(1);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
test('failed automatic preparation offers catalog recovery and session finish without manual generation',async({page})=>{
 let retry=0;await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};if(path==='/api/problems')data=[problem];if(path==='/api/training-sessions')data=[active];
  if(path==='/api/learning-curricula')data=[{evaluationId:'eval',bankId:'algo-mix-a-v2',createdAt:'2026-10-06T10:00:00Z',steps:[{category:'bfs',plan:{id:'failed',evaluationId:'eval',status:'READY',goal:'BFS를 연습하기',generationId:'draft',generationStatus:'BUILD_FAILED'},preparation:{status:retry?'MAPPED':'FAILED',message:retry?null:'자동 검증 실패'},candidate:retry?problem:null}]}];
  if(path==='/api/learning-curricula/plans/failed/prepare'){retry++;data={status:'MAPPED',problemVersion:'one'};}
  await route.fulfill({json:data});
 });await page.goto(base+'/#training');const pane=page.getByRole('region',{name:'다음 학습',exact:true});
 await expect(pane).toContainText('직접 문제를 생성할 필요는 없어요');await expect(pane.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();await pane.getByRole('button',{name:'기존 문제 다시 찾기',exact:true}).click();await expect(pane.getByRole('button',{name:'이 문제로 전환',exact:true})).toBeVisible();expect(retry).toBe(1);
});
test('late course lookup preserves the tab explicitly selected by the learner',async({page})=>{
 let release;const pending=new Promise(resolve=>{release=resolve;});let lookup=false;
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/training-courses/enrollments'){lookup=true;await pending;data=[enrollment('current','진행 중인 코스','active')];}
  await route.fulfill({json:data});
 });await page.goto(base+'/#training');await expect.poll(()=>lookup).toBe(true);
 const tab=page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true});await tab.click();release();await expect(page.getByRole('region',{name:'내 훈련 기록',exact:true})).toBeVisible();await expect(tab).toHaveAttribute('aria-current','page');
});
