import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
for(const width of [390,820,1440])test(`record layout, long notes and recoverable detail at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});let detailCalls=0;
 const note='경계 조건에서 빈 배열과 중복 원소를 다시 확인하기. '.repeat(12);
 const problems=[
  {version:'long-v1',title:'한밤중의 감자 창고에서 서로 겹치는 운송 기록을 정리하는 방법',category:'배열·문자열',accepted:1,confidence:'SHAKY',reflectionNote:note},
  {version:'long-v2',title:'서로 다른 출발점에서 만나는 두 탐험가',category:'너비 우선 탐색',accepted:0},
  {version:'long-v3',title:'검토 중인 문제의 이전 기록',category:'탐욕법',accepted:1,held:true},
  {version:'long-v4',title:'진단평가의 연결된 길 찾기',category:'그래프·최단 경로',accepted:1,diagnostic:true},
 ].map(p=>({...p,attempts:3,lastSubmitted:'2026-10-02T13:21:00Z',submissionsEnabled:true}));
 const submissions=problems.map((p,i)=>({id:'s'+i,problemVersion:p.version,verdict:i===1?'WA':'AC',language:i===1?'PYTHON':'JAVA',createdAt:p.lastSubmitted,problemHeld:p.held,status:'FINISHED',source:'// saved source\n'+('long code line '.repeat(40))}));
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());let data=[];
  if(url.pathname==='/api/me')data={id:'layout-user',nickname:'감자'};
  if(url.pathname==='/api/problems')data=problems;
  if(url.pathname==='/api/my/summary')data={submitted:4,attemptedProblems:4,solvedProblems:3};
  if(url.pathname==='/api/my/problems')data={total:4,items:problems};
  if(url.pathname==='/api/submissions')data=submissions;
  if(url.pathname==='/api/submissions/s1'){
   if(++detailCalls===1){await route.fulfill({status:503,json:{message:'잠시 후 다시 확인해 주세요.'}});return;}
   data=submissions[1];
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#mypage');const records=page.getByRole('region',{name:'문제와 제출 기록'});
 await expect(records.locator('.my-problem-row')).toHaveCount(4);
 await records.getByText('메모 보기',{exact:true}).click();await expect(records.getByText(note,{exact:true})).toBeVisible();
 await expect(records.locator('.my-problem-row').nth(2).getByRole('button',{name:'문제 풀기'})).toHaveCount(0);
 if(width===1440){
  const alignment=await records.evaluate(el=>{const head=el.querySelector('.my-column-head'),row=el.querySelector('.my-problem-row');return [...head.children].every((c,i)=>Math.abs(c.getBoundingClientRect().x-row.children[i].getBoundingClientRect().x)<1);});expect(alignment).toBe(true);
 }
 await records.getByText('메모 보기',{exact:true}).click();
 await records.screenshot({path:`/tmp/gamja-record-problems-${width}.png`});
 await records.getByRole('button',{name:'전체 제출',exact:true}).click();await expect(records.locator('.my-submission')).toHaveCount(4);
 await records.screenshot({path:`/tmp/gamja-record-submissions-${width}.png`});
 const opener=records.locator('.my-submission').nth(1);await opener.focus();await opener.press('Enter');
 const dialog=page.getByRole('dialog',{name:'제출 상세',exact:true});await expect(dialog.getByRole('alert')).toContainText('잠시 후');
 await dialog.getByRole('button',{name:'다시 불러오기'}).click();await expect(dialog.getByLabel('제출 당시 코드')).toContainText('// saved source');
 const bounds=await dialog.boundingBox();expect(bounds.x).toBeGreaterThanOrEqual(0);expect(bounds.x+bounds.width).toBeLessThanOrEqual(width+1);
 await dialog.screenshot({path:`/tmp/gamja-record-detail-${width}.png`});
 await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();await expect(opener).toBeFocused();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
