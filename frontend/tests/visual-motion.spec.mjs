import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
async function open(page){
 await page.setViewportSize({width:1440,height:950});
 await page.route('**/api/**',r=>{const path=new URL(r.request().url()).pathname;return r.fulfill({json:path==='/api/me'?{id:'motion-user',nickname:'감자'}:path==='/api/problems'?[{version:'motion',title:'움직임 확인',statement:'설명',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}]:[]});});
 await page.goto(base+'/#practice');await page.getByLabel('Main.java',{exact:true}).fill('// draft survives motion');
}
async function toggleFrames(page){return page.evaluate(()=>new Promise(resolve=>{
 const workspace=document.querySelector('.workspace'),rail=document.querySelector('.app-navigation'),label=rail.querySelector('.nav-label-text');const frames=[],start=performance.now();rail.querySelector('.sidebar-toggle').click();
 function frame(now){frames.push({time:now-start,width:rail.getBoundingClientRect().width,opacity:Number(getComputedStyle(label).opacity)});if(now-start<650)requestAnimationFrame(frame);else resolve(frames);}requestAnimationFrame(frame);
}));}
test('sidebar hides labels before shrinking and reveals after expansion; rapid reversal retains the editor',async({page})=>{
 await open(page);const editor=page.getByLabel('Main.java',{exact:true}),original=await editor.elementHandle();
 const close=await toggleFrames(page);expect(close.some(f=>f.time>70&&f.time<160&&f.opacity<.1&&f.width>170)).toBe(true);expect(close.some(f=>f.width>65&&f.width<170)).toBe(true);expect(close.at(-1).width).toBe(64);expect(close.at(-1).opacity).toBe(0);
 const expand=await toggleFrames(page);expect(expand.some(f=>f.time>80&&f.time<250&&f.width>100&&f.opacity===0)).toBe(true);expect(expand.at(-1).width).toBe(176);expect(expand.at(-1).opacity).toBe(1);
 await page.locator('.sidebar-toggle').click();await page.locator('.sidebar-toggle').click();await expect.poll(async()=>(await page.locator('.app-navigation').boundingBox()).width).toBe(176);expect(await editor.evaluate((n,old)=>n===old,original)).toBe(true);await expect(editor).toContainText('// draft survives motion');
 await page.screenshot({path:'/tmp/gamja-glass-workspace.png',animations:'disabled'});
});
test('reduced motion disables transitions and modal keeps keyboard dismissal and focus',async({page})=>{
 await page.emulateMedia({reducedMotion:'reduce'});await open(page);await page.getByRole('button',{name:'사이드바 접기'}).click();await expect(page.locator('.app-navigation')).toHaveCSS('width','64px');expect(await page.locator('.workspace').evaluate(n=>n.getAnimations({subtree:true}).length)).toBe(0);
 const trigger=page.getByRole('button',{name:'화면 설정',exact:true});await trigger.click();const dialog=page.getByRole('dialog',{name:'화면 설정',exact:true});await expect(dialog).toBeVisible();expect(await dialog.evaluate(n=>n.getAnimations().length)).toBe(0);await dialog.press('Escape');await expect(dialog).not.toBeVisible();await expect(trigger).toBeFocused();
});
