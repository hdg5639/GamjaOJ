import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
for(const width of [390,1440])test(`end failed unstarted plan with exact retry and preserve independent course at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});await page.addInitScript(theme=>localStorage.setItem('gamjaoj-theme',theme),width===390?'dark':'light');
 let endedAt=null,note='',requests=[];
 const sessions=[{id:'course',status:'ACTIVE',problemVersion:'one',goal:'코스의 목표',startedAt:'2026-10-06T10:00:00Z',submissions:0,accepted:0,pending:0}];
 await page.route('**/api/**',async route=>{const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',username:'learner'};
  if(path==='/api/problems')data=[{version:'one',title:'코스 문제',category:'구현',statement:'문제를 풀어요.',submissionsEnabled:true}];
  if(path==='/api/training-sessions')data=sessions;
  if(path==='/api/learning-curricula')data=[{evaluationId:'eval',bankId:'algo-mix-a-v2',createdAt:'2026-10-06T10:00:00Z',endedAt,endNote:note,steps:[{category:'bfs',plan:{id:'failed',status:'READY',goal:'BFS 연습',generationId:'draft',generationStatus:'BUILD_FAILED'},preparation:{status:'FAILED',message:'검증 실패'}}]}];
  if(path==='/api/learning-curricula/eval/end'){requests.push(req.postDataJSON());note=req.postDataJSON().note;endedAt||='2026-10-06T12:00:00Z';data={evaluationId:'eval',endedAt,note};if(requests.length===1)return route.abort();}
  await route.fulfill({json:data});
 });await page.goto(base+'/#training');const region=page.getByRole('region',{name:'나의 학습 계획'});
 await region.getByRole('button',{name:'학습 계획 종료',exact:true}).click();const modal=page.getByRole('dialog',{name:'학습 계획 종료',exact:true});
 await expect(modal).toContainText('다른 훈련 코스와 자유 연습은 유지');await modal.getByLabel('계획 마무리 메모 (선택)').fill('다른 목표부터 연습할게요');await modal.getByRole('button',{name:'이 학습 계획 종료하기',exact:true}).click();await expect(modal.getByRole('button',{name:'같은 계획 종료 요청 다시 확인'})).toBeEnabled();
 await page.reload();await region.getByRole('button',{name:'같은 학습 요청 다시 확인'}).click();await expect.poll(()=>requests.length).toBe(2);expect(requests[0]).toEqual(requests[1]);
 await expect(region).toContainText('종료한 학습 계획');await expect(region).toContainText('다른 목표부터 연습할게요');await expect(region.getByRole('button',{name:'학습 계획 종료',exact:true})).toHaveCount(0);await expect(region.getByRole('button',{name:'기존 문제 다시 찾기',exact:true})).toHaveCount(0);
 await expect(region.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();await expect(region.getByRole('progressbar',{name:'계획 목표 진행도'})).toHaveAttribute('value','0');expect(sessions[0].status).toBe('ACTIVE');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await page.screenshot({path:`/tmp/gamja-training-plan-ended-${width}.png`,fullPage:true});
});
