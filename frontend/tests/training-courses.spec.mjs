import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
const definitions=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/training-courses-v1.json',import.meta.url)));
const pool=JSON.parse(readFileSync(new URL('../../generation/thinking-problemset-v1.json',import.meta.url))),byVersion=new Map(pool.map(p=>[p.version,p]));
function view(course,id=null){let position=0;const steps=course.stages.flatMap(stage=>stage.versions.map(version=>{const p=byVersion.get(version);return {position:position++,stage:stage.title,goal:stage.goal,version,title:p.title,category:p.category,thinking:p.thinking,available:true,solved:false,sessionId:null,sessionStatus:null};}));return {enrollmentId:id,course,steps,solved:0,available:steps.length};}
for(const width of [390,1440])test(`enrollment, persisted retry, session switch and progress at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});await page.addInitScript(theme=>localStorage.setItem('gamjaoj-theme',theme),width===390?'dark':'light');
 let enrolled=[],sessions=[],keys=[],bodies=[],requests=new Map();const catalog=definitions.map(c=>view(c));
 const problems=pool.map(p=>({version:p.version,title:p.title,category:p.category,thinking:p.thinking,statement:'입력 규칙을 확인하고 결과를 출력하세요.',submissionsEnabled:true,sourceLimitBytes:65536}));
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'course-user',username:'learner'};
  if(path==='/api/problems')data=problems;
  if(path==='/api/training-sessions')data=sessions;
  if(path==='/api/training-courses')data=catalog;
  if(path==='/api/training-courses/enrollments'){if(req.method()==='POST'){if(!enrolled.length)enrolled=[view(definitions.find(c=>c.id===req.postDataJSON().courseId),'enrolled')];data=enrolled[0];}else data=enrolled;}
  if(path==='/api/training-courses/enrollments/enrolled/start'){
   keys.push(req.headers()['idempotency-key']);bodies.push(req.postDataJSON());const body=req.postDataJSON(),key=keys.at(-1);
   if(requests.has(key))data=requests.get(key);
   else{const step=enrolled[0].steps[body.position];for(const s of sessions)if(s.status==='ACTIVE'){s.status='ENDED';s.note=body.note;}
    data={id:key,status:'ACTIVE',problemVersion:step.version,goal:step.goal,startedAt:new Date().toISOString(),submissions:0,accepted:0,pending:0};sessions.unshift(data);step.sessionId=key;step.sessionStatus='ACTIVE';requests.set(key,data);if(keys.length===1)return route.abort();}
  }
  if(path.startsWith('/api/training-sessions/')&&!path.endsWith('/end'))data={session:sessions.find(s=>s.id===path.split('/').at(-1)),entries:[]};
  await route.fulfill({json:data});
 });
 const tab=()=>page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'훈련 코스',exact:true});
 await page.goto(base+'/#training');await tab().click();const course=page.getByRole('region',{name:'훈련 코스',exact:true});await expect(course.locator('.course-catalog>li')).toHaveCount(6);
 await course.locator('.course-catalog>li').first().getByRole('button',{name:'코스 살펴보기'}).click();const preview=page.getByRole('dialog',{name:'처음부터 쌓는 알고리즘'});await expect(preview).toContainText('선형 자료구조');await preview.getByRole('button',{name:'이 코스로 훈련하기'}).click();
 await expect(course.getByRole('heading',{name:'나의 훈련 코스'})).toBeVisible();await expect(course.getByRole('progressbar',{name:'코스 정답 진도'})).toHaveAttribute('value','0');
 await expect(course.locator('.learning-problem-list li')).toHaveCount(5);await expect(course.getByRole('button',{name:'직접 훈련 시작',exact:true})).toBeVisible();
 await course.locator('.learning-problem-list li').nth(1).getByRole('button').click();await expect(course.getByRole('heading',{name:'올바른 괄호쌍',exact:true})).toBeFocused();await expect(course.getByRole('heading',{name:'올바른 괄호쌍',exact:true})).toBeInViewport();
 await course.getByRole('button',{name:'훈련 시작',exact:true}).click();await expect(course.getByRole('button',{name:'같은 코스 요청 다시 확인'})).toBeEnabled();
 await page.reload();await tab().click();await course.getByRole('button',{name:'같은 코스 요청 다시 확인'}).click();await expect.poll(()=>keys.length).toBe(2);expect(keys[0]).toBe(keys[1]);expect(bodies[0]).toEqual(bodies[1]);expect(bodies[0].position).toBe(1);
 await expect(page.locator('#problem-title')).toHaveText('올바른 괄호쌍');await page.getByRole('button',{name:'훈련 기록',exact:true}).click();await expect(course.getByRole('button',{name:'훈련 마무리',exact:true})).toBeVisible();
 await course.locator('.learning-problem-list li').nth(2).getByRole('button').click();await course.getByRole('button',{name:'이 문제로 전환',exact:true}).click();const modal=page.getByRole('dialog',{name:'진행 중인 훈련 전환'});await modal.getByLabel('마무리 메모').fill('다음 자료구조 연습');await modal.getByRole('button',{name:'현재 훈련 마치고 전환'}).click();
 await expect.poll(()=>keys.length).toBe(3);expect(bodies[2].activeSessionId).toBe(keys[0]);expect(sessions.find(s=>s.id===keys[0]).note).toBe('다음 자료구조 연습');await expect(page.locator('#problem-title')).toHaveText('큐에 대해서');
 enrolled[0].steps[0].solved=true;enrolled[0].solved=1;await page.getByRole('button',{name:'훈련 기록',exact:true}).click();await page.evaluate(()=>window.dispatchEvent(new Event('gamjaoj-training-changed')));await expect(course.getByRole('progressbar',{name:'코스 정답 진도'})).toHaveAttribute('value','1');
 const left=await course.locator('.learning-plan-overview').boundingBox(),right=await course.locator('.learning-current-problem').boundingBox(),list=await course.locator('.learning-problem-list').boundingBox();expect(left.y+left.height).toBeLessThanOrEqual(list.y+1);expect(right.y+right.height).toBeLessThanOrEqual(list.y+1);if(width===1440)expect(Math.abs(left.y-right.y)).toBeLessThan(2);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);await page.locator('.training-hub-view').evaluate(el=>el.scrollTo(0,0));await page.screenshot({path:`/tmp/gamja-training-courses-${width}.png`,fullPage:true,animations:'disabled'});
 await course.getByRole('button',{name:'다른 코스 고르기'}).click();await expect(course.locator('.course-catalog>li')).toHaveCount(6);
 await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'학습 계획',exact:true}).click();await expect(page.getByRole('button',{name:'진단·수동 계획 만들기'})).toBeVisible();
 await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();await page.getByRole('region',{name:'내 훈련 기록',exact:true}).locator('.training-record-list>li').last().getByRole('button').click();await expect(page.getByRole('dialog',{name:'훈련 상세 기록'})).toContainText('다음 자료구조 연습');
});
test('load failure recovery and unavailable course enrollment guard',async({page})=>{
 let failed=true;const catalog=definitions.map(c=>view(c));catalog[0].available=11;catalog[0].steps[0].available=false;
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];if(path==='/api/me')data={id:'course-user',username:'learner'};if(path==='/api/training-courses'){if(failed)return route.fulfill({status:503,json:{message:'코스를 불러오지 못했어요.'}});data=catalog;}await route.fulfill({json:data});});
 await page.goto(base+'/#training');await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'훈련 코스',exact:true}).click();const course=page.getByRole('region',{name:'훈련 코스',exact:true});await expect(course.getByRole('alert')).toBeVisible();failed=false;await course.getByRole('button',{name:'다시 불러오기'}).click();await expect(course.locator('.course-catalog>li').first().getByRole('button',{name:'문제 준비 중'})).toBeDisabled();await expect(course.locator('.course-catalog>li').first()).toContainText('11 / 12문제');
});
