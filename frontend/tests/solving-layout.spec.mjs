import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const examples=Array.from({length:4},(_,i)=>({input:i===3?'1234567890 '.repeat(80):'1 2',output:'3',explanation:'예제 설명'}));
async function open(page,diagnostic){
 const question={itemId:'item',problemVersion:'layout',version:'layout',title:'긴 문제',statement:'조건을 확인합니다.\n'.repeat(120),examples,submissionsEnabled:true};
 const session={id:'session',status:'ACTIVE',items:[{id:'item',position:0,category:'implementation',difficulty:'EASY',status:'OPEN',attempts:0,pending:0}],current:question};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'layout',nickname:'테스트'};
  if(path==='/api/problems')data=[question];
  if(path==='/api/diagnostics')data=[session];
  if(path==='/api/diagnostics/session')data=session;
  await route.fulfill({json:data});
 });
 await page.goto(base+(diagnostic?'/#diagnostic':'/#practice'));
 await expect(page.getByLabel(diagnostic?'진단 Java 코드':'Main.java',{exact:true})).toBeVisible();
}
for(const diagnostic of [false,true])for(const width of [1024,1440])test(`independent solving panes and equal examples ${diagnostic?'diagnostic':'practice'} ${width}`,async({page})=>{
 await page.setViewportSize({width,height:800});await open(page,diagnostic);
 const problem=page.locator(diagnostic?'.diagnostic-workspace > article':'.problem-card');
 const editor=page.getByLabel(diagnostic?'진단 Java 코드':'Main.java',{exact:true});
 await editor.fill(Array.from({length:180},(_,i)=>`// line ${i}`).join('\n'));
 const scroller=page.locator(diagnostic?'.diagnostic-editor .cm-scroller':'.editor-view:not([hidden]) .cm-scroller');
 await scroller.evaluate(e=>e.scrollTop=0);
 await expect.poll(()=>problem.evaluate(e=>e.scrollHeight>e.clientHeight)).toBe(true);
 const before=await editor.boundingBox();
 await problem.hover();await page.mouse.wheel(0,500);
 await expect.poll(()=>problem.evaluate(e=>e.scrollTop)).toBeGreaterThan(0);
 expect(await scroller.evaluate(e=>e.scrollTop)).toBe(0);
 expect((await editor.boundingBox()).y).toBe(before.y);
 const top=await problem.evaluate(e=>e.scrollTop);
 await scroller.hover();await page.mouse.wheel(0,400);
 await expect.poll(()=>scroller.evaluate(e=>e.scrollTop)).toBeGreaterThan(0);
 expect(await problem.evaluate(e=>e.scrollTop)).toBe(top);
 await problem.evaluate(e=>e.scrollTop=e.scrollHeight);
 const fourth=problem.locator('.example').nth(3);
 const halves=await fourth.locator(':scope > div').evaluateAll(es=>es.map(e=>({width:e.getBoundingClientRect().width,x:e.getBoundingClientRect().x,y:e.getBoundingClientRect().y})));
 expect(Math.abs(halves[0].width-halves[1].width)).toBeLessThan(1);
 expect(halves[0].y).toBe(halves[1].y);
 expect(halves[1].x).toBeGreaterThan(halves[0].x);
 expect(await problem.evaluate(e=>e.scrollWidth<=e.clientWidth)).toBe(true);
 expect(await page.evaluate(()=>document.documentElement.scrollHeight<=innerHeight)).toBe(true);
 await page.screenshot({path:`/tmp/gamjaoj-solving-${diagnostic?'diagnostic':'practice'}-${width}.png`});
});
test('mobile examples stay equal and pane switching preserves the draft',async({page})=>{
 await page.setViewportSize({width:390,height:844});await open(page,false);
 const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('// draft');
 await page.getByRole('button',{name:'문제 보기',exact:true}).click();
 const problem=page.locator('.problem-card');await problem.evaluate(e=>e.scrollTop=e.scrollHeight);
 const widths=await problem.locator('.example').nth(3).locator(':scope > div').evaluateAll(es=>es.map(e=>e.getBoundingClientRect().width));
 expect(Math.abs(widths[0]-widths[1])).toBeLessThan(1);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'/tmp/gamjaoj-solving-mobile.png'});
 await page.getByRole('button',{name:'코드 작성',exact:true}).click();await expect(editor).toContainText('// draft');
});
