import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
async function open(page){
 const requests=[];
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;requests.push(path);
  return route.fulfill({json:path==='/api/me'?{id:'performance',nickname:'감자'}:path==='/api/problems'?[{version:'perf',title:'성능 회귀',statement:'# 문제\n\n설명을 유지합니다.',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}]:path.endsWith('/illustrations')?{canEdit:false,illustrations:[]}:[]});
 });
 await page.goto(base);await expect(page.getByRole('heading',{name:'문제 탐색',exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'성능 회귀 · perf 이어서 풀기',exact:true})).toBeVisible();
 return requests;
}
test('exploration defers solving and training; revisiting retains the live editor and undo',async({page})=>{
 const requests=await open(page);
 await expect(page.locator('.cm-editor')).toHaveCount(0);
 expect(requests.some(path=>path.endsWith('/illustrations')||path==='/api/followup-goals'||path==='/api/learning-tracks')).toBe(false);
 await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
 const editor=page.getByLabel('Main.java',{exact:true});await expect(editor).toBeVisible();
 await editor.fill('// retained');await editor.press('End');await editor.pressSequentially(' draft');
 const element=await editor.elementHandle();
 await page.getByRole('button',{name:'문제 탐색',exact:true}).click();
 await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
 expect(await editor.evaluate((node,old)=>node===old,element)).toBe(true);
 await expect(editor).toContainText('// retained draft');await editor.press('ControlOrMeta+z');
 await expect(editor).not.toContainText('// retained draft');await editor.press('ControlOrMeta+Shift+z');
 await expect(editor).toContainText('// retained draft');
});
test('resize coalesces pointer bursts and flushes the final value before release',async({page})=>{
 await page.setViewportSize({width:1440,height:950});await page.emulateMedia({reducedMotion:'reduce'});
 await open(page);await page.getByRole('button',{name:'문제 풀기',exact:true}).click();await expect(page.getByLabel('Main.java',{exact:true})).toBeVisible();
 const handle=page.getByRole('separator',{name:'문제와 편집기 비율',exact:true});const box=await handle.boundingBox();
 await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();
 const result=await handle.evaluate(async node=>{
  const start=node.getBoundingClientRect().x+node.getBoundingClientRect().width/2;
  let writes=0;const original=Storage.prototype.setItem;
  Storage.prototype.setItem=function(...args){if(args[0].includes('editor-layout'))writes++;return original.apply(this,args);};
  try{
   for(let i=1;i<=80;i++)node.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,pointerId:1,clientX:start+i,clientY:200}));
   const beforeFrame=writes;
   await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
   return {beforeFrame,writes};
  }finally{Storage.prototype.setItem=original;}
 });
 expect(result.beforeFrame).toBe(0);expect(result.writes).toBe(1);
 await page.mouse.up();expect(Number(await handle.getAttribute('aria-valuenow'))).toBeGreaterThan(36);
 const value=await handle.getAttribute('aria-valuenow');await page.reload();await expect(handle).toHaveAttribute('aria-valuenow',value);
});
