import {test,expect} from '@playwright/test';
import {expectSurfaceTint} from './surface-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
async function open(page){
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'appearance',nickname:'테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'appearance',title:'화면 설정 테스트',statement:'테스트',examples:[{input:'1',output:'2'}],submissionsEnabled:true,languages:[{id:'JAVA',label:'Java 8'}]}];
  if(path==='/api/editor/completions')data={items:[]};
  if(path==='/api/runs')data={id:'run',status:'FINISHED',verdict:'OK',stdout:'2',stderr:'test error',compileMessage:'test warning',language:'JAVA'};
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await expect(page.getByLabel('Main.java',{exact:true})).toBeVisible();
}
async function settings(page,tab='화면 색상'){
 // Reload first renders the anonymous header; wait for the authenticated one.
 await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible();
 await page.getByRole('button',{name:'화면 설정',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'화면 설정',exact:true});await expect(dialog).toBeVisible();
 await dialog.getByRole('button',{name:tab,exact:true}).click();return dialog;
}
test('live editor font and syntax colors retain the editor and undo history',async({page})=>{
 await open(page);const editor=page.getByLabel('Main.java',{exact:true});
 await editor.fill('public class Main { String text = "hi"; int n = 42; }');
 await editor.press('Control+End');await editor.pressSequentially(' // edit');
 const dialog=await settings(page,'에디터');
 await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption('consolas');
 await dialog.getByLabel('에디터 글자 크기',{exact:true}).focus();await dialog.getByLabel('에디터 글자 크기',{exact:true}).press('End');
 await dialog.getByLabel('에디터 배경 HEX',{exact:true}).fill('#112233');
 await dialog.getByLabel('키워드 HEX',{exact:true}).fill('#abcdef');
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('font-size','28px');
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(17, 34, 51)');
 await expect(page.locator('.code-editor .cm-scroller')).toHaveCSS('font-family',/Consolas/);
 await expect(page.locator('.cm-line span').filter({hasText:/^public$/})).toHaveCSS('color','rgb(171, 205, 239)');
 await editor.focus();await editor.press('ControlOrMeta+z');await expect(editor).not.toContainText('// edit');
 await expect(editor).toContainText('public class Main');
 await page.reload();await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('font-size','28px');
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(17, 34, 51)');
});
for(const width of [390,1440])test(`bundled editor fonts render real glyphs and preserve editing at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await open(page);
 const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('public class Main { String text = "한글"; }');
 await editor.press('End');await editor.pressSequentially(' // keep undo');
 const original=await editor.elementHandle(),cdp=await page.context().newCDPSession(page);
 await cdp.send('DOM.enable');await cdp.send('CSS.enable');
 const actualFonts=async()=>{const {root}=await cdp.send('DOM.getDocument');const {nodeId}=await cdp.send('DOM.querySelector',{nodeId:root.nodeId,selector:'.code-editor .cm-line'});if(!nodeId)return '';return (await cdp.send('CSS.getPlatformFontsForNode',{nodeId})).fonts.filter(f=>f.isCustomFont&&f.glyphCount>0).map(f=>f.familyName).join(' ');};
 let dialog=await settings(page,'에디터');
 for(const [id,family] of [['jetbrains',/JetBrains Mono/],['fira',/Fira Code/],['d2coding',/D2Coding/]]){
  await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption(id);await expect.poll(actualFonts).toMatch(family);
  expect(await editor.evaluate((n,old)=>n===old,original)).toBe(true);await expect(editor).toContainText('// keep undo');
 }
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();await editor.focus();await editor.press('ControlOrMeta+z');await expect(editor).not.toContainText('// keep undo');
 await page.reload();await expect.poll(actualFonts).toMatch(/D2Coding/);
 dialog=await settings(page,'에디터');await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption('custom');await dialog.getByLabel('설치된 글꼴 이름',{exact:true}).fill('Gamja Nonexistent Font 9274');await expect(dialog.getByRole('status').filter({hasText:'기기에서 찾지 못해'})).toBeVisible();
 await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption('jetbrains');await expect(dialog.getByText('기기에서 찾지 못해',{exact:false})).toHaveCount(0);await expect.poll(actualFonts).toMatch(/JetBrains Mono/);
});
test('app palettes are separate for light and dark, remembered, and resettable',async({page})=>{
 await open(page);let dialog=await settings(page);
 await dialog.getByRole('button',{name:'라이트',exact:true}).click();
 await dialog.getByLabel('화면 배경 HEX',{exact:true}).fill('#112233');
 await dialog.getByLabel('패널 배경 HEX',{exact:true}).fill('#445566');
 await dialog.getByLabel('사이드바 배경 HEX',{exact:true}).fill('#123456');
 await expect(page.locator('body')).toHaveCSS('background-color','rgb(17, 34, 51)');
 await expectSurfaceTint(page.locator('.practice-grid'),[68,85,102]);
 await expectSurfaceTint(page.locator('.app-navigation'),[18,52,86]);
 await dialog.getByRole('button',{name:'다크',exact:true}).click();await dialog.getByLabel('화면 배경 HEX',{exact:true}).fill('#334455');
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();
 await expect(page.getByRole('button',{name:'라이트 모드로 전환',exact:true})).toBeVisible();
 await page.reload();await expect(page.locator('body')).toHaveCSS('background-color','rgb(51, 68, 85)');
 await page.getByRole('button',{name:'라이트 모드로 전환',exact:true}).click();await expect(page.locator('body')).toHaveCSS('background-color','rgb(17, 34, 51)');
 dialog=await settings(page);await dialog.getByRole('button',{name:'전체 기본값',exact:true}).click();
 await expect(page.locator('body')).toHaveCSS('background-color','rgb(245, 248, 247)');
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();await page.reload();
 await expect(page.locator('html')).toHaveAttribute('data-custom-colors','false');
});
test('console colors apply to results, inputs, compiler warnings and standard error',async({page})=>{
 await open(page);const dialog=await settings(page,'터미널');
 await dialog.getByLabel('터미널 배경 HEX',{exact:true}).fill('#123456');
 await dialog.getByLabel('터미널 글자 HEX',{exact:true}).fill('#abcdef');
 await dialog.getByLabel('입력·오류 배경 HEX',{exact:true}).fill('#334455');
 await dialog.getByLabel('실패·오류 표시 HEX',{exact:true}).fill('#ff3366');
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 const console=page.getByRole('region',{name:'실행 결과',exact:true});
 await expect(console).toHaveCSS('background-color','rgb(18, 52, 86)');
 await expect(console.getByLabel('테스트 1 출력')).toHaveCSS('color','rgb(171, 205, 239)');
 await expect(console.locator('.compiler-message')).toHaveCSS('background-color','rgb(51, 68, 85)');
 await expect(console.locator('.compiler-message')).toHaveCSS('color','rgb(255, 51, 102)');
 await page.getByRole('button',{name:'테스트 케이스 추가',exact:true}).click();await page.getByRole('button',{name:'+ 케이스 추가',exact:true}).click();
 await expect(page.getByLabel('추가 1 · 입력',{exact:true})).toHaveCSS('background-color','rgb(51, 68, 85)');
});
test('invalid hex stays editable without overwriting saved colors and custom fonts fall back',async({page})=>{
 await open(page);const dialog=await settings(page,'에디터');
 const color=dialog.getByLabel('에디터 배경 HEX',{exact:true});await color.fill('#123456');await color.fill('#bad');
 await expect(color).toHaveAttribute('aria-invalid','true');
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(18, 52, 86)');
 await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption('custom');
 await dialog.getByLabel('설치된 글꼴 이름',{exact:true}).fill('My Local Font');
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();
 await expect(page.locator('.code-editor .cm-scroller')).toHaveCSS('font-family',/My Local Font.*monospace/);
 await page.reload();await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(18, 52, 86)');
});
test('malformed saved settings are ignored and font size is clamped before rendering',async({page})=>{
 await page.addInitScript(()=>localStorage.setItem('gamjaoj-appearance-v1',JSON.stringify({light:{canvas:'red;display:none'},editor:{'editor-background':'url(bad)'},font:'unknown',fontSize:1000})));
 await open(page);await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('font-size','28px');
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(43, 43, 43)');
 await expect(page.locator('html')).toHaveAttribute('data-custom-colors','false');
});
test('settings remain usable without local storage and on mobile',async({page})=>{
 await page.setViewportSize({width:390,height:844});
 await page.addInitScript(()=>{Storage.prototype.getItem=()=>{throw new Error('disabled');};Storage.prototype.setItem=()=>{throw new Error('disabled');};});
 await open(page);const dialog=await settings(page,'에디터');
 await dialog.getByLabel('에디터 배경 HEX',{exact:true}).fill('#123456');
 await dialog.getByLabel('에디터 글꼴',{exact:true}).selectOption('fira');
 expect(await dialog.evaluate(e=>e.scrollWidth<=e.clientWidth)).toBe(true);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:'/tmp/gamjaoj-appearance-mobile.png'});
 await dialog.getByRole('button',{name:'닫기',exact:true}).click();
 await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(18, 52, 86)');
});

for(const width of [390,1440])test(`settings scroll within a fixed glass shell without live backdrop blur at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:844});await open(page);
 const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('public class Main {}');await editor.press('End');await editor.pressSequentially(' // keep undo');
 const dialog=await settings(page,'에디터'),content=dialog.locator('.appearance-content'),header=dialog.locator('.modal-head');
 await expect(dialog).toHaveCSS('overflow-y','hidden');await expect(dialog).toHaveCSS('backdrop-filter','none');await expect(content).toHaveCSS('overflow-y','auto');
 expect(await dialog.evaluate(el=>getComputedStyle(el,'::backdrop').backdropFilter)).toBe('none');
 await dialog.evaluate(el=>Promise.all(el.getAnimations().map(animation=>animation.finished)));
 const editorNode=await editor.elementHandle(),before=await header.boundingBox();
 const bounds=await content.boundingBox();await page.mouse.move(bounds.x+bounds.width/2,bounds.y+Math.min(150,bounds.height/2));await page.mouse.wheel(0,900);
 await expect.poll(()=>content.evaluate(el=>el.scrollTop)).toBeGreaterThan(100);
 expect((await header.boundingBox()).y).toBe(before.y);expect(await dialog.evaluate(el=>el.scrollTop)).toBe(0);
 await dialog.getByRole('button',{name:'이 영역 색상 초기화'}).scrollIntoViewIfNeeded();await expect(dialog.getByRole('button',{name:'이 영역 색상 초기화'})).toBeInViewport();
 await dialog.getByLabel('에디터 배경 HEX',{exact:true}).fill('#123456');await expect(page.locator('.code-editor .cm-editor')).toHaveCSS('background-color','rgb(18, 52, 86)');
 await page.screenshot({path:`/tmp/gamja-appearance-scroll-${width}.png`});
 await page.keyboard.press('Escape');await expect(dialog).not.toBeVisible();await expect(page.getByRole('button',{name:'화면 설정',exact:true})).toBeFocused();
 expect(await editor.evaluate((node,original)=>node===original,editorNode)).toBe(true);await editor.focus();await editor.press('ControlOrMeta+z');await expect(editor).not.toContainText('keep undo');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
