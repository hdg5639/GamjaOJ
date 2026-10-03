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

for(const diagnostic of [false,true])for(const layout of ['columns','default'])test(`${layout} all six pane orders preserve the live editor and undo ${diagnostic?'diagnostic':'practice'}`,async({page})=>{
 await page.setViewportSize({width:1440,height:900});await open(page,diagnostic);
 const editor=page.getByLabel(diagnostic?'진단 Java 코드':'Main.java',{exact:true});
 await editor.fill('// before');await editor.press('ArrowLeft');await editor.press('ControlOrMeta+End');await editor.pressSequentially(' after');
 if(layout==='columns')await page.getByRole('button',{name:'세로 3분할',exact:true}).click();
 const grid=page.locator(diagnostic?'.diagnostic-workspace':'.practice-grid');
 const panes={problem:grid.locator(':scope > article'),code:editor,console:grid.locator('.run-console')};
 for(const order of ['code-console-problem','console-problem-code','problem-console-code','console-code-problem','code-problem-console','problem-code-console']){
  const labels={problem:'문제',code:'코드',console:'터미널'};
  await page.locator('summary[aria-label="패널 순서 변경"]:visible').click();
  const slots=page.locator('.pane-order-editor:visible [data-pane-slot]');
  for(let i=0;i<3;i++){const current=await slots.evaluateAll(es=>es.map(e=>e.dataset.pane));const target=order.split('-')[i];if(current[i]!==target)await slots.nth(current.indexOf(target)).dragTo(slots.nth(i));}
  await page.locator('summary[aria-label="패널 순서 변경"]:visible').click();
  await expect(grid).toHaveAttribute('data-pane-order',order);
  const boxes=await Promise.all(order.split('-').map(p=>panes[p].boundingBox()));
  if(layout==='columns'){expect(boxes[0].x+boxes[0].width).toBeLessThanOrEqual(boxes[1].x);expect(boxes[1].x+boxes[1].width).toBeLessThanOrEqual(boxes[2].x);}else {expect(boxes[0].x).toBeLessThan(boxes[1].x);expect(boxes[1].y).toBeLessThan(boxes[2].y);}
  await expect(editor).toContainText('// before after');
 }
  await editor.focus();await editor.press('ControlOrMeta+z');await expect(editor).toContainText('// before');await expect(editor).not.toContainText(' after');
 await page.locator('summary[aria-label="패널 순서 변경"]:visible').click();await page.locator('.pane-order-editor:visible [data-pane-slot]').nth(2).dragTo(page.locator('.pane-order-editor:visible [data-pane-slot]').nth(0));
 await page.reload();await expect(grid).toHaveAttribute('data-pane-order','console-code-problem');
 if(layout==='default'){await page.locator('summary[aria-label="패널 순서 변경"]:visible').click();await page.getByRole('button',{name:'좌우 반전',exact:true}).click();await expect(grid).toHaveAttribute('data-mirrored','true');return;}
 const handle=grid.getByRole('separator',{name:'첫 번째와 두 번째 패널 비율',exact:true});
 const before=await panes.console.boundingBox();await handle.focus();await handle.press('ArrowRight');
 expect((await panes.console.boundingBox()).width).toBeGreaterThan(before.width);
 await page.screenshot({path:`/tmp/gamjaoj-reordered-${diagnostic?'diagnostic':'practice'}.png`});
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

for(const diagnostic of [false,true])for(const width of [1024,1440])test(`three vertical panes preserve drafts and resize independently ${diagnostic?'diagnostic':'practice'} ${width}`,async({page})=>{
 await page.setViewportSize({width,height:800});await open(page,diagnostic);
 const choice=page.getByRole('button',{name:'세로 3분할',exact:true});
 const editor=page.getByLabel(diagnostic?'진단 Java 코드':'Main.java',{exact:true});
 const split=page.locator(diagnostic?'.diagnostic-split':'.practice-view .split-stack');
 const problem=page.locator(diagnostic?'.diagnostic-workspace > article':'.problem-card');
 const console=split.locator('.run-console');
 await expect(choice).toHaveAttribute('aria-pressed','false');await editor.fill('// layout draft');
 await choice.click();await expect(split).toHaveAttribute('data-layout','columns');
 await expect(editor).toContainText('// layout draft');
 const boxes=await Promise.all([problem.boundingBox(),split.locator('.split-top').boundingBox(),console.boundingBox()]);
 expect(boxes[0].x+boxes[0].width).toBeLessThanOrEqual(boxes[1].x);
 expect(boxes[1].x+boxes[1].width).toBeLessThanOrEqual(boxes[2].x);
 expect(Math.abs(boxes[0].y-(boxes[2].y-8))).toBeLessThan(2);
 expect(boxes[1].height).toBeGreaterThan(100);
 const handle=split.getByRole('separator');await expect(handle).toHaveAttribute('aria-orientation','vertical');
 await handle.focus();await handle.press('ArrowLeft');await expect(handle).toHaveAttribute('aria-valuenow','63');
 const resized=await split.locator('.split-top').boundingBox();expect(resized.width).toBeLessThan(boxes[1].width);
 await page.reload();await expect(choice).toHaveAttribute('aria-pressed','true');await expect(handle).toHaveAttribute('aria-valuenow','63');
 await expect(editor).toContainText('// layout draft');
 await expect(console).toBeInViewport({ratio:1});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`/tmp/gamjaoj-columns-${diagnostic?'diagnostic':'practice'}-${width}.png`});
 await page.setViewportSize({width:390,height:844});await expect(split).toHaveAttribute('data-layout','default');
 await expect(editor).toContainText('// layout draft');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.setViewportSize({width,height:800});await expect(split).toHaveAttribute('data-layout','columns');
 await page.getByRole('button',{name:'기본 배치',exact:true}).click();await expect(handle).toHaveAttribute('aria-orientation','horizontal');
 await expect(editor).toContainText('// layout draft');
});
