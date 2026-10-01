import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test(`problem history and personal activity stay separate at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});let runLists=0;
 const records=Array.from({length:51},(_,i)=>({id:`s${i}`,problemVersion:i===50?'v2':'v1',status:'FINISHED',verdict:i===50?'WA':'AC',createdAt:'2026-09-26T00:00:00Z',language:'JAVA',source:`// submission ${i}`}));
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());let data=[];
  if(url.pathname==='/api/me')data={id:'my-test',nickname:'기록'};
  if(url.pathname==='/api/problems')data=['v1','v2'].map(version=>({version,title:`문제 ${version}`,statement:'설명',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}));
  if(url.pathname==='/api/submissions')data=url.searchParams.has('problemVersion')?records.filter(r=>r.problemVersion===url.searchParams.get('problemVersion')).slice(0,50):records.slice(Number(url.searchParams.get('page')||0)*Number(url.searchParams.get('size')||50),(Number(url.searchParams.get('page')||0)+1)*Number(url.searchParams.get('size')||50));
  if(url.pathname.startsWith('/api/submissions/'))data=records.find(r=>url.pathname.endsWith('/'+r.id));
  if(url.pathname==='/api/my/summary')data={submitted:51,attemptedProblems:2,solvedProblems:1};
  if(url.pathname==='/api/my/problems')data={total:2,items:['v1','v2'].map((version,i)=>({version,title:`문제 ${version}`,attempts:i?1:50,accepted:i?0:50,lastSubmitted:'2026-09-26T00:00:00Z'}))};
  if(url.pathname==='/api/runs'&&route.request().method()==='GET')runLists++;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await page.getByLabel('Main.java',{exact:true}).fill('// keep my draft');
 await page.getByRole('button',{name:'제출 기록',exact:true}).click();
 await page.getByText('최근 제출 내역',{exact:false}).click();
 await expect(page.locator('#submission-results .record-list')).not.toContainText('v2');
 await page.locator('#submission-results li button').first().click();
 await page.getByLabel('풀이할 문제').selectOption('v2');
 await expect(page.locator('#submission-heading')).toHaveCount(0);
 await page.getByText('최근 제출 내역',{exact:false}).click();
 await expect(page.locator('#submission-results .record-list')).toContainText('v2');
 await expect(page.locator('#submission-results .record-list')).not.toContainText('v1');
 await page.getByRole('button',{name:'마이페이지',exact:true}).click();
 const my=page.getByRole('region',{name:'마이페이지',exact:true});
 await expect(my.getByText('문제 v1',{exact:true})).toBeVisible();
 await expect(my.getByText('문제 v2',{exact:true})).toBeVisible();
 await my.getByRole('button',{name:'전체 제출',exact:true}).click();
 await expect(my.locator('.my-records li')).toHaveCount(20);
 await my.getByRole('navigation',{name:'상단 기록 페이지',exact:true}).getByRole('button',{name:'다음',exact:true}).click();
 await expect(my.locator('.my-records li')).toHaveCount(20);
 await my.getByRole('navigation',{name:'기록 페이지',exact:true}).getByRole('button',{name:'다음',exact:true}).click();
 await expect(my.locator('.my-records li')).toHaveCount(11);
 await my.locator('.my-submission').last().click();await expect(my.getByLabel('제출 당시 코드')).toHaveText('// submission 50');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-my-page-${width}.png`,fullPage:true});
 await page.getByRole('button',{name:'문제 풀기',exact:true}).click();await page.getByLabel('풀이할 문제').selectOption('v1');
 if(width===390)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
 await expect(page.getByLabel('Main.java',{exact:true})).toContainText('// keep my draft');
 await expect(page.getByText('최근 실행 내역',{exact:false})).toHaveCount(0);
 expect(runLists).toBe(0);
});

for(const width of [390,1440])test(`my problem pages reset on tab change and clamp after refresh at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});let total=41;
 const problems=Array.from({length:41},(_,i)=>({version:`v${i}`,title:`기록 문제 ${i+1}`,attempts:1,accepted:1,lastSubmitted:'2026-10-01T00:00:00Z',statement:'설명',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}));
 await page.route('**/api/**',route=>{
  const url=new URL(route.request().url()),index=Number(url.searchParams.get('page')||0);let data=[];
  if(url.pathname==='/api/me')data={id:'pages-user',username:'learner',nickname:'감자'};
  if(url.pathname==='/api/problems')data=problems;
  if(url.pathname==='/api/my/summary')data={submitted:total,attemptedProblems:total,solvedProblems:total};
  if(url.pathname==='/api/my/problems')data={total,items:problems.slice(index*20,Math.min((index+1)*20,total))};
  if(url.pathname==='/api/submissions'&&url.searchParams.has('size')){
   expect(url.searchParams.get('size')).toBe('20');
   data=problems.slice(index*20,Math.min((index+1)*20,total)).map(p=>({id:'s'+p.version,problemVersion:p.version,verdict:'AC',language:'JAVA',createdAt:'2026-10-01T00:00:00Z'}));
  }
  return route.fulfill({json:data});
 });
 await page.goto(base+'/#mypage');const my=page.getByRole('region',{name:'마이페이지',exact:true});
 const top=my.getByRole('navigation',{name:'상단 기록 페이지',exact:true}),bottom=my.getByRole('navigation',{name:'기록 페이지',exact:true});
 await expect(my.locator('.my-records li')).toHaveCount(20);await expect(top).toBeInViewport();
 await page.screenshot({path:`/tmp/gamja-my-pagination-top-${width}.png`,fullPage:true});
 await top.getByRole('button',{name:'다음',exact:true}).click();await expect(my.locator('.my-records li').first()).toContainText('기록 문제 21');
 await bottom.getByRole('button',{name:'다음',exact:true}).click();await expect(my.locator('.my-records li')).toHaveCount(1);await expect(my.locator('#my-records-heading')).toContainText('41–41번째');
 await my.getByRole('button',{name:'전체 제출',exact:true}).click();await expect(my.locator('.my-records li')).toHaveCount(20);await expect(my.locator('.my-records li').first()).toContainText('기록 문제 1');
 await my.getByRole('button',{name:'풀어본 문제',exact:true}).click();await top.getByRole('button',{name:'다음',exact:true}).click();
 await expect(my.locator('.my-records li').first()).toContainText('기록 문제 21');await top.getByRole('button',{name:'다음',exact:true}).click();await expect(my.locator('.my-records li')).toHaveCount(1);
 total=3;await my.getByRole('button',{name:'기록 새로고침',exact:true}).click();await expect(my.locator('.my-records li')).toHaveCount(3);
 await expect(my.locator('#my-records-heading')).toContainText('1–3번째');await expect(top).toHaveCount(0);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
