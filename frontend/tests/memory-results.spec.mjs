import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test(`execution and submission memory metrics are shown at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:960});
 await page.route('**/api/**',route=>{
  const r=route.request(),p=new URL(r.url()).pathname;let data=[];
  if(p==='/api/me')data={id:'memory-user',username:'learner',nickname:'감자'};
  if(p==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  if(p==='/api/problems')data=[{version:'sum-v1',title:'두 수의 합',statement:'합을 출력하세요.',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}];
  if(p==='/api/runs'&&r.method()==='POST')data={id:'run',status:'FINISHED',verdict:'OK',stdout:'3',wallMs:17,memoryPeakBytes:33554432};
  if(p==='/api/submissions'&&r.method()==='POST')data={id:'submission',problemVersion:'sum-v1',status:'FINISHED',verdict:'AC',wallMs:49,memoryPeakBytes:50331648,tests:[{number:1,verdict:'AC',wallMs:17,memoryPeakBytes:33554432},{number:2,verdict:'AC',wallMs:49,memoryPeakBytes:50331648}],testCount:2};
  return route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');if(width<=800)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();const console=page.getByRole('region',{name:'실행 결과',exact:true});
 await expect(console.locator('.execution-metrics')).toContainText('32.00 MiB');await expect(console.locator('.execution-metrics')).toContainText('17 ms');
 await page.getByRole('button',{name:'제출 후 채점하기',exact:true}).click();
 await expect(console.locator('.submission-view .execution-metrics')).toContainText('48.00 MiB');await expect(console.locator('.submit-tests')).toContainText('메모리 32.00 MiB');await expect(console.locator('.submit-tests')).toContainText('메모리 48.00 MiB');
 await console.locator('.submission-view .execution-metrics').scrollIntoViewIfNeeded();await page.screenshot({path:`/tmp/gamja-memory-results-${width}.png`,fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
