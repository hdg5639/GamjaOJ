import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,820,1440])test(`growth connects real progress to compact unsolved targets at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});
 const problems=Array.from({length:36},(_,i)=>({version:`growth-${i}`,title:i===0?'':`성장 연습 ${i}`,statement:'설명',sampleInput:'1 2',sampleOutput:'3',category:'구현',tags:['구현'],mine:i===0,shared:true,submissionsEnabled:true,solveStatus:i%3===0?'SOLVED':'UNATTEMPTED',thinking:{layer:i%9+1,source:'CURATED_ESTIMATE',rationale:'조건 연결',insight:2,implementation:2,edgeCases:2}}));
 problems[8].thinking.source='AUTHOR_ESTIMATE';problems[5].thinking.source='MODEL_ESTIMATE';
 const growth={layer:4,name:'이어붙이기',eligibleProblems:7,categories:3,excludedProblems:1,nextLayer:5,nextSolved:2,required:5,evidence:[7,7,6,5,2,1,0,0,0]};
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'growth-user',username:'learner',nickname:'감자'};
  if(path==='/api/problems')data=problems;
  if(path==='/api/my/growth')data=growth;
  if(path==='/api/my/summary')data={submitted:10,attemptedProblems:8,solvedProblems:8};
  if(path==='/api/my/problems')data={total:0,items:[]};
  return route.fulfill({json:data});
 });
 await page.goto(base);if(width===820)await page.getByRole('button',{name:'다크 모드로 전환'}).click();
 const catalog=page.getByRole('region',{name:'문제 목록',exact:true}),summary=catalog.getByRole('region',{name:'나의 성장 겹'}),rows=catalog.locator('.catalog-list>li');
 await expect(summary).toContainText('4겹 · 이어붙이기');await expect(summary.getByRole('progressbar')).toHaveAttribute('value','2');await expect(rows).toHaveCount(20);await expect(rows.first()).toContainText('연습 문제 ·');
 const heights=await rows.evaluateAll(nodes=>nodes.slice(1).map(n=>n.getBoundingClientRect().height));expect(Math.max(...heights)).toBeLessThan(width===390?125:95);
 await summary.getByText('성장 겹은 어떻게 정해지나요?').click();await expect(summary).toContainText('같은 문제의 반복 제출은 한 번');await summary.getByText('성장 겹은 어떻게 정해지나요?').click();
 await page.screenshot({path:`/tmp/gamja-growth-${width}.png`,fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await summary.getByRole('button',{name:/다음 겹 문제 찾아보기/}).click();await expect(catalog.getByLabel('난이도',{exact:true})).toHaveValue('MIN5');await expect(rows).toHaveCount(15);
 await expect(rows.locator('.catalog-progress')).toHaveText(Array(15).fill('미제출'));await expect(rows.filter({hasText:'9겹'})).toHaveCount(3);await expect(rows.filter({hasText:'성장 연습 8'})).toHaveCount(0);
 await catalog.getByRole('button',{name:'검색 초기화',exact:true}).click();await expect(rows).toHaveCount(20);
 await summary.getByRole('button',{name:'내 풀이 기록',exact:true}).click();await expect(page.getByRole('region',{name:'마이페이지',exact:true}).getByRole('region',{name:'나의 성장 겹'})).toContainText('4겹 · 이어붙이기');
});
test('growth failure can retry without inventing a tier',async({page})=>{
 let requests=0;
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'retry-user',nickname:'감자'};
  if(path==='/api/my/growth'){requests++;if(requests===1)return route.fulfill({status:503,json:{message:'잠시 후 다시 시도해주세요.'}});data={layer:0,eligibleProblems:0,categories:0,excludedProblems:0,nextLayer:1,nextSolved:0,required:5,evidence:Array(9).fill(0)};}
  return route.fulfill({json:data});
 });
 await page.goto(base);const summary=page.getByRole('region',{name:'나의 성장 겹'});await expect(summary.getByRole('alert')).toBeVisible();await expect(summary.getByRole('progressbar')).toHaveCount(0);await summary.getByRole('button',{name:'성장 기록 다시 불러오기'}).click();await expect(summary).toContainText('첫 겹을 쌓는 중');await expect(summary.getByRole('progressbar')).toHaveAttribute('value','0');
});
