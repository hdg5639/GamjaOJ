import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
for(const width of [390,820,1440])test(`skip reasons, curriculum and separate history at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});const skips=[],trainingKeys=[];let evaluationPosts=0,training=[];
 const question=n=>({itemId:'i'+n,problemVersion:'diag'+n,title:'접근 방법 확인 '+n,statement:'두 정수의 합을 구하세요.',sampleInput:'1 2',sampleOutput:'3'});
 let session={id:'diagnostic-current',bankId:'algo-mix-a-v1',status:'ACTIVE',createdAt:'2026-10-03T00:00:00Z',current:question(0),items:[{id:'i0',category:'dp',difficulty:'EASY',status:'OPEN',attempts:0,pending:0},{id:'i1',category:'bfs',difficulty:'MEDIUM',status:'OPEN',attempts:0,pending:0}]};
 const histories=Array.from({length:14},(_,i)=>({...session,id:'old'+i,status:'COMPLETED',current:null,items:session.items.map(item=>({...item,status:'SKIPPED'}))}));
 const problems=[{version:'basic-dp',title:'차근차근 쌓는 점수',category:'동적 계획법',difficulty:'EASY',submissionsEnabled:true,statement:'한 칸씩 점수를 더하세요.',sampleInput:'1',sampleOutput:'1'},{version:'held-dp',title:'보류된 후보',category:'동적 계획법',difficulty:'EASY',submissionsEnabled:true,problemHeld:true}];
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'curriculum-user',nickname:'감자'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=problems;
  if(path==='/api/training-sessions'){
   if(req.method()==='POST'){trainingKeys.push(req.headers()['idempotency-key']);const body=req.postDataJSON();expect(body.problemVersion).toBe('basic-dp');expect(body.goal).toContain('동적 계획법');training=[{id:'training-basic',...body,status:'ACTIVE',startedAt:'2026-10-03T00:00:00Z',pending:0,submissions:0,accepted:0}];if(trainingKeys.length===1){await route.abort();return;}expect(trainingKeys[1]).toBe(trainingKeys[0]);data=training[0];}else data=training;
  }
  if(path==='/api/diagnostics')data=[session,...histories];
  if(path==='/api/diagnostics/diagnostic-current')data=session;
  if(path.endsWith('/skip')){
   const reason=req.postDataJSON().reason;skips.push(reason);const id=path.split('/').at(-2);
   session={...session,items:session.items.map(item=>item.id===id?{...item,status:'SKIPPED',skipReason:reason}:item)};
   session.current=session.items.find(item=>item.status==='OPEN')?question(1):null;session.status=session.current?'ACTIVE':'COMPLETED';
   if(skips.length===1){await route.abort();return;}data=session;
  }
  if(path.endsWith('/evaluations')){if(req.method()==='POST')evaluationPosts++;data=[{id:'facts',status:'FACTS_ONLY',facts:{complete:true,items:session.items.map(i=>({...i,itemId:i.id}))},corrections:[]}];}
  if(path.endsWith('/profile'))data={categories:[{id:'dp',selected:true,items:session.items.slice(0,1),observations:[],alsoSeen:[],ruleIds:[]},{id:'bfs',selected:true,items:session.items.slice(1),observations:[],alsoSeen:[],ruleIds:[]}],rules:[]};
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#diagnostic');await expect(page.getByRole('heading',{name:'접근 방법 확인 0'})).toBeVisible();
 await page.getByRole('button',{name:'건너뛰기',exact:true}).click();await page.getByRole('button',{name:'접근 방법을 모르겠어요',exact:true}).click();
 await expect.poll(()=>skips.length).toBe(1);await expect(page.getByRole('button',{name:'요청 다시 확인'})).toBeEnabled();await page.reload();await page.getByRole('button',{name:'요청 다시 확인'}).click();
 await expect(page.getByRole('heading',{name:'접근 방법 확인 1'})).toBeVisible();
 await page.getByRole('button',{name:'건너뛰기',exact:true}).click();await page.getByRole('button',{name:'시간이 부족해요',exact:true}).click();
 const roadmap=page.getByRole('region',{name:'추천 커리큘럼'});await expect(roadmap).toContainText('동적 계획법의 접근 방법부터');await expect(roadmap).not.toContainText('너비 우선 탐색(BFS)의 접근 방법부터');await expect(roadmap).not.toContainText('보류된 후보');
 expect(evaluationPosts).toBe(0);expect(skips).toEqual(['NOT_SURE','NOT_SURE','NO_TIME']);
 await page.getByRole('navigation',{name:'보고서 목차'}).getByRole('button',{name:'추천 커리큘럼'}).click();expect(new URL(page.url()).hash).toBe('#diagnostic');
 await roadmap.screenshot({path:`/tmp/gamja-diagnostic-roadmap-${width}.png`});
 await page.getByRole('button',{name:'지난 진단',exact:true}).click();const history=page.getByRole('dialog',{name:'지난 진단',exact:true});await expect(history.locator('li')).toHaveCount(10);await history.getByRole('button',{name:'다음',exact:true}).click();await expect(history.locator('li')).toHaveCount(5);await history.screenshot({path:`/tmp/gamja-diagnostic-history-${width}.png`});await page.keyboard.press('Escape');
 await page.getByRole('button',{name:'차근차근 쌓는 점수 · 기초 훈련 시작'}).click();await expect.poll(()=>trainingKeys.length).toBe(1);await expect(page.getByRole('button',{name:'같은 기초 훈련 요청 다시 확인'})).toBeEnabled();await page.reload();await page.getByRole('button',{name:'지난 진단',exact:true}).click();await page.getByRole('dialog',{name:'지난 진단',exact:true}).getByRole('button',{name:'2문항 · 완료'}).first().click();
 await page.getByRole('button',{name:'같은 기초 훈련 요청 다시 확인'}).click();await expect(page.getByRole('button',{name:'문제 풀기',exact:true})).toHaveAttribute('aria-pressed','true');expect(trainingKeys).toHaveLength(2);expect(evaluationPosts).toBe(0);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
