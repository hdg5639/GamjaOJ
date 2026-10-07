import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test(`one batch preserves per-input output, failures and CPU metrics at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:960});let requests=[],polls=0;
 await page.route('**/api/**',route=>{
  const r=route.request(),p=new URL(r.url()).pathname;let data=[];
  if(p==='/api/me')data={id:'batch-user',username:'batch',nickname:'감자'};
  if(p==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  if(p==='/api/problems')data=[{version:'batch-v1',title:'병렬 실행',statement:'입력을 출력하세요.',sampleInput:'0',sampleOutput:'0',examples:[0,1,2,3].map(i=>({input:String(i),output:String(i)})),submissionsEnabled:true}];
  if(p==='/api/runs'&&r.method()==='POST'){requests.push(r.postDataJSON());data={id:'batch',status:'QUEUED',runCases:[]};}
  if(p==='/api/runs/batch'){
   polls++;data={id:'batch',status:'FINISHED',verdict:'RE',compileMessage:'',execution:{timeMetric:'CPU'},runCases:[0,1,2,3].map(i=>({number:i+1,verdict:i===1?'RE':'OK',stdout:i===1?'':String(i),stderr:i===1?'case 2 failed':'',cpuMs:10+i,wallMs:100+i,memoryPeakBytes:(i+1)*1048576,outputTruncated:false}))};
  }
  return route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');
 if(width<=800)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 await expect(page.getByLabel('테스트 4 출력')).toHaveText('3');
 expect(requests).toHaveLength(1);expect(requests[0].inputs).toEqual(['0','1','2','3']);expect(requests[0].input).toBeUndefined();expect(polls).toBe(1);
 const rows=page.locator('.run-detail');await expect(rows).toHaveCount(4);
 await expect(rows.nth(0)).toHaveAttribute('data-passed','true');await expect(rows.nth(1)).toHaveAttribute('data-passed','false');await expect(rows.nth(2)).toHaveAttribute('data-passed','true');
 await expect(rows.nth(1)).toContainText('case 2 failed');await expect(rows.nth(3)).toContainText('CPU 시간 13 ms');await expect(rows.nth(3)).toContainText('4.00 MiB');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
