import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
async function open(page){
 await page.setViewportSize({width:1440,height:950});
 await page.route('**/api/**',r=>{const path=new URL(r.request().url()).pathname;return r.fulfill({json:path==='/api/me'?{id:'motion-user',nickname:'감자'}:path==='/api/problems'?[{version:'motion',title:'움직임 확인',statement:'설명',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}]:[]});});
 await page.goto(base+'/#practice');await page.getByLabel('Main.java',{exact:true}).fill('// draft survives motion');
}
async function toggleFrames(page){return page.evaluate(()=>new Promise(resolve=>{
 const workspace=document.querySelector('.workspace'),rail=document.querySelector('.app-navigation'),label=rail.querySelector('.nav-label-text');const frames=[],start=performance.now();rail.querySelector('.sidebar-toggle').click();
 function frame(now){frames.push({time:now-start,width:rail.getBoundingClientRect().width,opacity:Number(getComputedStyle(label).opacity),header:document.querySelector('#global-header').getBoundingClientRect().height});if(now-start<650)requestAnimationFrame(frame);else resolve(frames);}requestAnimationFrame(frame);
}));}
test('sidebar hides labels before shrinking and reveals after expansion; rapid reversal retains the editor',async({page})=>{
 await open(page);const editor=page.getByLabel('Main.java',{exact:true}),original=await editor.elementHandle();
 const close=await toggleFrames(page);expect(close.some(f=>f.time>70&&f.time<160&&f.opacity<.1&&f.width>170)).toBe(true);expect(close.some(f=>f.width>65&&f.width<170)).toBe(true);expect(close.at(-1).width).toBe(64);expect(close.at(-1).opacity).toBe(0);
 expect(close.some(f=>f.time<130&&f.header===60)).toBe(true);expect(close.some(f=>f.header>44&&f.header<60)).toBe(true);expect(close.at(-1).header).toBe(44);
 const alignment=await page.locator('.app-navigation').evaluate(n=>{const center=node=>{const r=node.getBoundingClientRect();return r.x+r.width/2;};return {rail:center(n),toggle:center(n.querySelector('.sidebar-toggle svg')),menu:center(n.querySelector('.workspace-nav svg'))};});expect(Math.abs(alignment.toggle-alignment.menu)).toBeLessThan(.6);expect(Math.abs(alignment.toggle-alignment.rail)).toBeLessThan(.6);
 const expand=await toggleFrames(page);expect(expand.some(f=>f.time>80&&f.time<250&&f.width>100&&f.opacity===0)).toBe(true);expect(expand.at(-1).width).toBe(176);expect(expand.at(-1).opacity).toBe(1);
 expect(expand.some(f=>f.header>44&&f.header<60)).toBe(true);expect(expand.at(-1).header).toBe(60);
 await page.locator('.sidebar-toggle').click();await page.locator('.sidebar-toggle').click();await expect.poll(async()=>(await page.locator('.app-navigation').boundingBox()).width).toBe(176);expect(await editor.evaluate((n,old)=>n===old,original)).toBe(true);await expect(editor).toContainText('// draft survives motion');
 await page.screenshot({path:'/tmp/gamja-glass-workspace.png',animations:'disabled'});
});
test('reduced motion disables transitions and modal keeps keyboard dismissal and focus',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});await open(page);await page.getByRole('button',{name:'사이드바 접기'}).click();await expect(page.locator('.app-navigation')).toHaveCSS('width','64px');expect(await page.locator('.workspace').evaluate(n=>n.getAnimations({subtree:true}).length)).toBe(0);
 const trigger=page.getByRole('button',{name:'화면 설정',exact:true});await trigger.click();const dialog=page.getByRole('dialog',{name:'화면 설정',exact:true});await expect(dialog).toBeVisible();expect(await dialog.evaluate(n=>n.getAnimations().length)).toBe(0);await dialog.press('Escape');await expect(dialog).not.toBeVisible();await expect(trigger).toBeFocused();
});
for(const width of [390,1440]) test(`uniform glass surfaces remain translucent in light and custom dark themes at ${width}px`,async({page})=>{
 await open(page);await page.setViewportSize({width,height:950});
 const trigger=page.getByRole('button',{name:'화면 설정',exact:true});await trigger.click();const dialog=page.getByRole('dialog',{name:'화면 설정',exact:true});
 for(const dark of [false,true]) {
  if(dark){await dialog.getByRole('button',{name:'다크',exact:true}).click();await dialog.getByRole('button',{name:/밤바다/}).click();}
  for(const surface of [page.locator('.header-slot'),page.locator('.app-navigation'),dialog,dialog.locator('.modal-head')]) {
   await expect(surface).toHaveCSS('background-image','none');await expect(surface).toHaveCSS('backdrop-filter',/blur/);
   const alpha=await surface.evaluate(n=>{const ctx=document.createElement('canvas').getContext('2d');ctx.fillStyle=getComputedStyle(n).backgroundColor;ctx.fillRect(0,0,1,1);return ctx.getImageData(0,0,1,1).data[3]/255;});expect(alpha).toBeGreaterThan(.5);expect(alpha).toBeLessThan(.95);
  }
  await expect(dialog.locator('.modal-body')).toHaveCSS('background-color','rgba(0, 0, 0, 0)');
  await expect(page.locator('body')).toHaveCSS('background-image',/linear-gradient/);
  const previousWash=await page.locator('body').evaluate(n=>getComputedStyle(n).backgroundImage);
  await dialog.getByLabel('화면 배경 HEX',{exact:true}).fill(dark?'#171b2b':'#f1efe9');
  await expect.poll(()=>page.locator('body').evaluate(n=>getComputedStyle(n).backgroundImage)).not.toBe(previousWash);
  await expect(page.locator('body')).toHaveCSS('background-color',dark?'rgb(23, 27, 43)':'rgb(241, 239, 233)');
  const edge=dialog.locator('.appearance-color').first(),oldEdge=await edge.evaluate(n=>getComputedStyle(n).borderBottomColor);
  await dialog.getByLabel('테두리 HEX',{exact:true}).fill('#887766');
  await expect.poll(()=>edge.evaluate(n=>getComputedStyle(n).borderBottomColor)).not.toBe(oldEdge);


  const box=await dialog.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(width);
  await page.screenshot({path:`/tmp/gamja-glass-modal-${dark?'dark':'light'}-${width}.png`,animations:'disabled'});
 }
 await dialog.press('Escape');await expect(dialog).not.toBeVisible();await expect(trigger).toBeFocused();await page.screenshot({path:`/tmp/gamja-glass-sidebar-${width}.png`,animations:'disabled'});
});
