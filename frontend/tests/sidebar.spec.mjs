import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1024,1440])test(`sidebar preference applies across screens at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});let account='sidebar-a';
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  let data=[];
  if(path==='/api/me')data={id:account,nickname:account};
  if(path==='/api/problems')data=[{version:'v1',title:'사이드바 테스트',statement:'설명',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}];
  if(path==='/api/my/summary')data={submitted:0,attemptedProblems:0,solvedProblems:0};
  if(path==='/api/my/problems')data={total:0,items:[]};
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await page.getByLabel('Main.java',{exact:true}).fill('// preserve layout draft');
 const header=page.locator('#global-header');
 await expect(header.locator('.sidebar-toggle')).toHaveCount(0);
 // A first visit starts expanded; the collapsed choice below is then remembered per account.
 await expect(page.getByRole('button',{name:'사이드바 접기'})).toHaveAttribute('aria-expanded','true');
 await page.getByRole('button',{name:'사이드바 접기'}).click();
 const sidebar=page.locator('#learning-navigation');
 for(const name of ['문제 탐색','선택 진단','내 문제 생성','훈련 기록','마이페이지','문제 풀기']){
  await page.getByRole('navigation',{name:'작업 화면'}).getByRole('button',{name,exact:true}).click();
  await expect(page.getByRole('button',{name:'사이드바 펼치기'})).toHaveAttribute('aria-expanded','false');
  await expect.poll(async()=>(await sidebar.boundingBox()).width).toBe(width>800?64:40);
  expect((await header.boundingBox()).height).toBeLessThanOrEqual(44);
  const rail=await sidebar.boundingBox(),toggle=await sidebar.locator('.sidebar-toggle').boundingBox();
  expect(rail.width).toBe(width>800?64:40);
  expect(toggle.x).toBeGreaterThanOrEqual(rail.x);
  expect(toggle.x+toggle.width).toBeLessThanOrEqual(rail.x+rail.width);
  expect(toggle.y+toggle.height).toBeLessThanOrEqual((await sidebar.locator('.workspace-nav').boundingBox()).y);
  await page.getByRole('button',{name:'사이드바 펼치기'}).press('Enter');
  await expect(page.getByRole('button',{name:'사이드바 접기'})).toHaveAttribute('aria-expanded','true');
  expect((await header.boundingBox()).height).toBeGreaterThan(44);
  if(width>800)await expect.poll(async()=>(await sidebar.boundingBox()).width).toBe(176);
  const label=await sidebar.locator('.nav-section-label').boundingBox(),collapse=await sidebar.locator('.sidebar-toggle').boundingBox();
  expect(collapse.x).toBeGreaterThanOrEqual(label.x+label.width);
  expect(Math.abs(collapse.y+collapse.height/2-label.y-label.height/2)).toBeLessThan(2);
  expect(collapse.x+collapse.width).toBeLessThanOrEqual((await sidebar.boundingBox()).x+(await sidebar.boundingBox()).width);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.getByRole('button',{name:'사이드바 접기'}).click();
 }
 await expect(page.getByLabel('Main.java',{exact:true})).toContainText('// preserve layout draft');
 await page.getByRole('button',{name:'사이드바 펼치기'}).click();
 await page.screenshot({path:`/tmp/gamja-sidebar-expanded-${width}.png`});
 await page.getByRole('button',{name:'문제 탐색',exact:true}).click();await expect(page.getByRole('button',{name:'사이드바 접기'})).toBeVisible();
 await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
 await page.reload();await expect(page.getByRole('button',{name:'사이드바 접기'})).toBeVisible();
 await expect(page.getByLabel('Main.java',{exact:true})).toContainText('// preserve layout draft');
 await page.getByRole('button',{name:'사이드바 접기'}).click();
 await page.getByRole('button',{name:'내 설정',exact:true}).click();
 expect((await header.boundingBox()).height).toBeLessThanOrEqual(44);
 await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
 await page.screenshot({path:`/tmp/gamja-sidebar-collapsed-${width}.png`});
 // Account a keeps its collapsed choice; an account without a saved choice starts expanded.
 account='sidebar-b';await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
 await expect(page.locator('.workspace')).toHaveAttribute('data-sidebar-collapsed','false');
 account='sidebar-a';await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
 await expect(page.locator('.workspace')).toHaveAttribute('data-sidebar-collapsed','true');
});
