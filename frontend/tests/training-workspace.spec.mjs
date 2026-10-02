import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
for(const width of [390,820,1710])test(`plan and current problem above selectable steps; exact switch recovery at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});
 await page.addInitScript(()=>{localStorage.setItem('gamjaoj-theme','dark');localStorage.setItem('gamjaoj-appearance-v1',JSON.stringify({dark:{canvas:'#101827',surface:'#18243a',subtle:'#22334d',ink:'#e6eefb',muted:'#a7b8d1',line:'#354760',green:'#568bc9'}}));});
 const problems=[{version:'one',title:'양코리에 남은 음표',category:'배열·문자열',difficulty:'EASY',statement:'음표를 세세요.',submissionsEnabled:true},{version:'two',title:'소원이 머무는 길',category:'너비 우선 탐색',difficulty:'EASY',statement:'길을 찾으세요.',submissionsEnabled:true}];
 const plans=[{id:'p1',evaluationId:'e',observationIndex:0,sourceKind:'SELF_REPORT',goal:'두 포인터로 창 상태를 재사용하기',status:'ACTIVE',sessionId:'p1',problemVersion:'one'},{id:'p2',evaluationId:'e',observationIndex:1,sourceKind:'SELF_REPORT',goal:'방문 상태를 구분해 최단 경로 구하기',status:'READY'}];
 let sessions=[{id:'p1',status:'ACTIVE',problemVersion:'one',goal:plans[0].goal,startedAt:'2026-10-03T10:00:00Z',submissions:1,accepted:0,pending:0}],keys=[],bodies=[];
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/problems')data=problems;
  if(path==='/api/training-sessions')data=sessions;
  if(path==='/api/learning-curricula')data=[{evaluationId:'e',bankId:'algo-mix-a-v2',createdAt:'2026-10-03T10:00:00Z',steps:plans.map((plan,i)=>({plan:{...plan},category:i?'bfs':'arrays-strings',basis:'SELF_REPORT',problemTitle:plan.problemVersion?problems[i].title:null,candidate:plan.status==='READY'?problems[i]:null,preparation:{status:'MAPPED'},progress:plan.sessionId?{submissions:1,accepted:0,pending:0,latestVerdict:'WA'}:null}))}];
  if(path==='/api/learning-curricula/switch'){
   keys.push(req.headers()['idempotency-key']);bodies.push(req.postDataJSON());
   plans[0].status='TRAINING_ENDED';Object.assign(plans[1],{status:'ACTIVE',sessionId:'p2',problemVersion:'two'});
   sessions=[{id:'p2',status:'ACTIVE',problemVersion:'two',goal:plans[1].goal,startedAt:'2026-10-03T10:30:00Z',submissions:0,accepted:0,pending:0},{...sessions.find(s=>s.id==='p1'),status:'ENDED',note:bodies[0].note}];data=plans[1];
   if(keys.length===1)return route.abort();
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#training');
 const learning=page.getByRole('region',{name:'나의 학습 계획'}),left=page.getByRole('region',{name:'계획 선택과 진행도'}),right=page.getByRole('region',{name:'다음 학습'}),list=page.getByRole('region',{name:'계획 문제 목록'});
 await expect(right.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();
 await expect(right.getByRole('button',{name:'훈련 이어 풀기',exact:true})).toBeEnabled();
 await expect(list.locator('li')).toHaveCount(2);await expect(list.getByRole('button',{name:'수동 설정·근거 확인'})).toHaveCount(0);
 const lb=await left.boundingBox(),rb=await right.boundingBox(),bottom=await list.boundingBox();
 expect(lb.y+lb.height).toBeLessThanOrEqual(bottom.y+1);expect(rb.y+rb.height).toBeLessThanOrEqual(bottom.y+1);
 if(width===1710){expect(Math.abs(lb.y-rb.y)).toBeLessThan(2);expect(lb.x+lb.width).toBeLessThanOrEqual(rb.x+1);}
 const finish=await right.getByRole('button',{name:'훈련 마무리',exact:true}).boundingBox();expect(finish.height).toBeLessThan(52);
 await list.locator('[data-plan-id="p2"] button').click();
 await expect(right.getByRole('heading',{name:'소원이 머무는 길',exact:true})).toBeFocused();
 await expect(list.locator('[data-plan-id="p2"] button')).toHaveAttribute('aria-current','step');
 await page.locator('.training-hub-view').evaluate(el=>el.scrollTo(0,0));await page.screenshot({path:`/tmp/gamja-training-workspace-${width}.png`,fullPage:true});
 await right.getByRole('button',{name:'이 문제로 전환',exact:true}).click();
 const modal=page.getByRole('dialog',{name:'훈련 문제 전환'});await modal.getByLabel('현재 훈련 마무리 메모').fill('첫 문제의 코드를 보존하고 다음 단계로');await modal.getByRole('button',{name:'저장하고 이 문제로 전환'}).click();
 await expect(modal.getByRole('button',{name:'같은 전환 요청 다시 확인'})).toBeEnabled();
 await page.reload();await learning.getByRole('button',{name:'같은 학습 요청 다시 확인'}).click();
 await expect.poll(()=>keys.length).toBe(2);expect(keys[0]).toBe(keys[1]);expect(bodies[0]).toEqual(bodies[1]);expect(bodies[0]).toEqual({planId:'p2',problemVersion:'two',activeSessionId:'p1',note:'첫 문제의 코드를 보존하고 다음 단계로'});
 await expect(page.getByRole('heading',{name:'소원이 머무는 길',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'훈련 기록',exact:true}).click();await expect(right).toContainText('현재 진행 중');
 await expect(right.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();expect(sessions.find(s=>s.id==='p1').note).toBe('첫 문제의 코드를 보존하고 다음 단계로');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
