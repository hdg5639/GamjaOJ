import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
function calendar(){const end=new Date('2026-10-02T00:00:00Z');return Array.from({length:365},(_,i)=>{const date=new Date(end);date.setUTCDate(date.getUTCDate()-364+i);return {date:date.toISOString().slice(0,10),solved:i>350?i%4:0};});}
async function fixture(page,categories){
 let reflection={problemVersion:'v1',submissionId:null,latestAcceptedSubmissionId:'s1',confidence:null,note:''},task=null,failSave=false,posts=0;
 const problem={version:'v1',title:'기억할 풀이',statement:'입력의 합을 구해요.',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true,category:'배열·문자열'};
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url());let data=[];
  if(url.pathname==='/api/me')data={id:'learning-user',username:'learner',nickname:'감자'};
  if(url.pathname==='/api/problems')data=[problem,{...problem,version:'v2',title:'처음 보는 그래프',category:'너비 우선 탐색'}];
  if(url.pathname==='/api/my/performance')data={items:[{submissionId:'s1',title:'기억할 풀이',language:'JAVA',submittedAt:'2026-10-02T00:00:00Z',maxWallMs:389,maxMemoryBytes:null,executionProfile:{language:'JAVA'},eligibility:'UNKNOWN_PROFILE',comparisonKey:null}],hasMore:false};
  if(url.pathname==='/api/my/summary')data={submitted:8,attemptedProblems:5,solvedProblems:1};
  if(url.pathname==='/api/my/growth')data={layer:0,nextLayer:1,nextSolved:1,required:5,eligibleProblems:1,categories:1,evidence:[1,0,0,0,0,0,0,0,0]};
  if(url.pathname==='/api/my/problems')data={total:1,items:[{...problem,attempts:8,accepted:1,lastSubmitted:'2026-10-02T00:00:00Z',confidence:reflection.confidence,reflectionNote:reflection.note}]};
  if(url.pathname==='/api/my/learning')data={start:'2025-10-03',end:'2026-10-02',timezone:'Asia/Seoul',days:calendar(),activeDays:10,currentStreak:2,longestStreak:5,practicedProblems:5,dominantCategory:'배열·문자열',categories:[{category:'배열·문자열',attempted:5,solved:1},{category:'너비 우선 탐색',attempted:0,solved:0}],explore:[{version:'v2',title:'처음 보는 그래프',category:'너비 우선 탐색',difficulty:'EASY',reason:'최근 90일 동안 도전하지 않은 분야예요.'}],revisit:reflection.confidence==='REVISIT'?[{...problem,confidence:'REVISIT'}]:[]};
  if(url.pathname==='/api/my/reflections'){
   if(req.method()==='PUT'){
    if(failSave){await route.fulfill({status:503,json:{message:'잠시 후 다시 저장해 주세요.'}});return;}
    const body=req.postDataJSON();reflection={...reflection,submissionId:body.confidence?'s1':null,confidence:body.confidence,note:body.note};
   }
   data=reflection;
  }
  if(url.pathname==='/api/submissions/s1')data={id:'s1',problemVersion:'v1',status:'FINISHED',verdict:'AC',language:'JAVA',source:'public class Main {}',createdAt:'2026-10-02T00:00:00Z',input:null};
  if(url.pathname==='/api/ai/status')data={enabled:true,operator:false};
  if(url.pathname==='/api/ai/tasks'){
   if(req.method()==='POST'){posts++;const body=req.postDataJSON();expect(body.strong).toBe(false);expect(body.kind).toBe('ANALYSIS');expect(body.question).toContain('코드 품질 회고');task={id:'review1',kind:'ANALYSIS',status:'COMPLETED',model:'gpt-6-luna',effort:'low',result:{summary:'합산 과정은 명확해요.',observations:['반복문은 O(N), 저장 공간은 O(1)이에요.'],nextSteps:['경계 입력을 혼자 다시 확인해 보세요.'],uncertainty:'다음에 혼자 풀 수 있는지는 직접 확인해 주세요.'}};data=task;}
   else data=task?[task]:[];
  }
  if(url.pathname==='/api/my/learning'&&categories)data.categories=categories;
  await route.fulfill({json:data});
 });
 return {fail(value){failSave=value;},posts:()=>posts};
}
for(const width of [390,820,1440])test(`grass, personal reflection and explicit cached AI review at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});const state=await fixture(page);await page.goto(base+'/#mypage');
 const my=page.getByRole('region',{name:'마이페이지',exact:true});
 await expect(my.getByRole('region',{name:'풀이 잔디'}).locator('button.activity-day')).toHaveCount(365);
 const today=my.getByRole('button',{name:'2026-10-02 정답 0문제',exact:true});await today.focus();await today.press('ArrowUp');
 await expect(my.getByRole('button',{name:'2026-10-01 정답 3문제',exact:true})).toBeFocused();
 await expect(my.getByText('배열·문자열에 도전이 많이 모였어요.',{exact:false})).toBeVisible();expect(state.posts()).toBe(0);
 const box=async name=>my.getByRole('region',{name,exact:true}).boundingBox();
 const [growth,grass,balance,next,records]=await Promise.all(['나의 성장 겹','풀이 잔디','유형 균형','다음 학습','문제와 제출 기록'].map(box));
 if(width>=1100){expect(growth.x+growth.width).toBeLessThan(grass.x);expect(Math.abs(growth.y-grass.y)).toBeLessThan(2);expect(balance.x).toBe(growth.x);expect(next.x).toBe(grass.x);expect(Math.abs(balance.y-next.y)).toBeLessThan(2);expect(balance.y).toBeGreaterThanOrEqual(growth.y+growth.height);expect(records.width).toBeGreaterThan(grass.width);}
 else {expect(grass.y).toBeGreaterThanOrEqual(growth.y+growth.height);expect(balance.y).toBeGreaterThanOrEqual(grass.y+grass.height);expect(next.y).toBeGreaterThanOrEqual(balance.y+balance.height);}
 expect(records.y).toBeGreaterThanOrEqual(Math.max(balance.y+balance.height,next.y+next.height));
 await page.screenshot({path:`/tmp/gamja-learning-overview-${width}.png`,fullPage:true});
 if(width===1440){await page.getByRole('button',{name:'다크 모드로 전환'}).click();await expect.poll(()=>page.evaluate(()=>getComputedStyle(document.querySelector('.activity-calendar-grid .level-0')).backgroundColor===getComputedStyle(document.querySelector('.activity-legend .level-0')).backgroundColor)).toBe(true);await page.screenshot({path:'/tmp/gamja-learning-overview-dark.png',fullPage:true});await page.getByRole('button',{name:'라이트 모드로 전환'}).click();}
 await my.getByRole('button',{name:'기억할 풀이 풀이 돌아보기'}).click();
 const reflection=my.getByRole('region',{name:'풀이 자신감'});await reflection.getByRole('button',{name:'다시 풀어야 함',exact:true}).click();
 await expect(reflection.getByRole('button',{name:'다시 풀어야 함',exact:true})).toHaveAttribute('aria-pressed','true');
 await reflection.getByLabel('다음에 볼 짧은 메모').fill('경계 조건을 다시 정리');state.fail(true);await reflection.getByRole('button',{name:'메모 저장'}).click();
 await expect(reflection.getByRole('alert')).toBeVisible();await expect(reflection.getByLabel('다음에 볼 짧은 메모')).toHaveValue('경계 조건을 다시 정리');
 state.fail(false);await reflection.getByRole('button',{name:'메모 저장'}).click();await expect(my.getByRole('region',{name:'다시 풀 문제'})).toBeVisible();
 await my.getByRole('button',{name:'빠른 코드 분석'}).click();await expect(my.getByText('합산 과정은 명확해요.',{exact:true})).toBeVisible();expect(state.posts()).toBe(1);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-learning-${width}.png`,fullPage:true});
 await page.reload();await page.getByRole('button',{name:'마이페이지',exact:true}).click();await my.getByRole('button',{name:'기억할 풀이 풀이 돌아보기'}).click();await expect(reflection.getByLabel('다음에 볼 짧은 메모')).toHaveValue('경계 조건을 다시 정리');await expect(my.getByText('합산 과정은 명확해요.',{exact:true})).toBeVisible();expect(state.posts()).toBe(1);
 await reflection.getByRole('button',{name:'확실히 풀 수 있음',exact:true}).click();await expect(my.getByRole('region',{name:'다시 풀 문제'})).toHaveCount(0);
 await reflection.getByRole('button',{name:'평가 지우기',exact:true}).click();await expect(reflection.getByLabel('다음에 볼 짧은 메모')).toHaveCount(0);
 await page.getByRole('dialog',{name:'제출 상세',exact:true}).getByRole('button',{name:'닫기',exact:true}).click();await my.getByRole('button',{name:'처음 보는 그래프 풀기'}).click();await expect(page.getByLabel('풀이할 문제')).toHaveValue('v2');
});
test('learning fetch failure stays distinct from an empty calendar',async({page})=>{
 await fixture(page);await page.route('**/api/my/learning',route=>route.fulfill({status:503,json:{message:'기록 조회 실패'}}));await page.goto(base+'/#mypage');
 await expect(page.getByRole('region',{name:'마이페이지',exact:true}).getByRole('alert')).toContainText('학습 기록을 불러오지 못했어요');await expect(page.locator('.activity-calendar-grid')).toHaveCount(0);
 await expect(page.getByRole('region',{name:'나의 성장 겹',exact:true})).toContainText('첫 겹을 쌓는 중');await expect(page.locator('.my-stats')).toContainText('정식 제출');
});
for(const width of [390,1440])test(`many learning categories stay compact and remain reachable at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});
 const categories=Array.from({length:36},(_,i)=>({category:`유형 ${String(i).padStart(2,'0')}`,attempted:i<13?13-i:0,solved:i<13?1:0,available:4}));
 await fixture(page,categories);await page.goto(base+'/#mypage');
 const balance=page.getByRole('region',{name:'유형 균형'}),rows=balance.locator('.category-distribution >div');
 await expect(balance.getByRole('button',{name:'도전한 유형 13'})).toHaveAttribute('aria-pressed','true');await expect(rows).toHaveCount(6);await expect(rows.first()).toContainText('유형 00');
 const seen=new Set();
 for(const [scope,count,pages] of [['도전한 유형 13',13,3],['아직 안 푼 유형 23',23,4]]){
  await balance.getByRole('button',{name:scope}).click();await expect(balance.locator('.category-range')).toContainText('1–6번째');
  const names=[];for(let p=0;p<pages;p++){expect(await rows.count()).toBeLessThanOrEqual(6);names.push(...await rows.locator(':scope >span').allTextContents());if(p<pages-1)await balance.getByRole('navigation',{name:'유형 분포 페이지'}).getByRole('button',{name:'다음',exact:true}).click();}
  expect(new Set(names).size).toBe(count);names.forEach(n=>seen.add(n));
 }
 expect(seen.size).toBe(36);await balance.getByRole('button',{name:'도전한 유형 13'}).click();await expect(rows.first()).toContainText('유형 00');
 await expect(page.getByRole('region',{name:'다음 학습'}).getByRole('button',{name:'처음 보는 그래프 풀기'})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await balance.screenshot({path:`/tmp/gamja-category-overview-${width}.png`,animations:'disabled'});
});

test('AC performance records show missing memory and profile without skill scoring',async({page})=>{
 await fixture(page);await page.goto(base+'/#mypage');
 const history=page.getByRole('region',{name:'AC 성능 이력',exact:true});
 await expect(history.getByText('389 ms',{exact:false})).toBeVisible();
 await expect(history.getByText('메모리 미측정',{exact:false})).toBeVisible();
 await expect(history.getByText('실행 조건 확인 불가',{exact:true})).toBeVisible();
 await history.getByText('실행 profile',{exact:true}).click();
 await expect(history.locator('pre')).toContainText('JAVA');
 await expect(history.getByRole('button',{name:'다음',exact:true})).toBeDisabled();
 await expect(page.getByRole('region',{name:'효율 재도전',exact:true})).toHaveCount(0);
});
