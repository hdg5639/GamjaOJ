import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
test('reopening settings and my page preserves state without refetching',async({page})=>{
 let integrations=0,summary=0,records=0;
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'cached-user',nickname:'감자',username:'gamja'};
  if(path==='/api/my/summary'){summary++;data={submitted:0,attemptedProblems:0,solvedProblems:0};}
  if(path==='/api/my/problems'){records++;data={total:0,items:[]};}
  if(path==='/api/integrations')integrations++;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#mypage');await expect(page.getByRole('heading',{name:'감자님의 풀이 기록'})).toBeVisible();await expect(page.getByText('기록을 불러오는 중…')).toHaveCount(0);
 await page.getByRole('button',{name:'내 설정',exact:true}).click();await expect(page.getByRole('heading',{name:'내 설정',exact:true})).toBeVisible();await expect.poll(()=>integrations).toBe(1);
 await page.getByLabel('닉네임').fill('보존된 닉네임');await page.getByRole('button',{name:'문제 풀기',exact:true}).first().click();
 await page.getByRole('button',{name:'내 설정',exact:true}).click();await expect(page.getByLabel('닉네임')).toHaveValue('보존된 닉네임');expect(integrations).toBe(1);
 await page.getByRole('button',{name:'문제 풀기',exact:true}).first().click();await expect(page.getByRole('heading',{name:'감자님의 풀이 기록'})).toBeVisible();expect(summary).toBe(1);expect(records).toBe(1);
 await page.getByRole('button',{name:'기록 새로고침',exact:true}).click();await expect.poll(()=>summary).toBe(2);await expect.poll(()=>records).toBe(2);
});
