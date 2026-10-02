import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const languages=[{id:'JAVA',label:'Java 8'},{id:'CPP',label:'C++17'},{id:'PYTHON',label:'Python 3.12'}];
async function workspace(page,completion=()=>({items:[]})){
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'console-regression',nickname:'테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'console-test',title:'콘솔 테스트',statement:'테스트',examples:Array.from({length:6},()=>({input:'1',output:'3'})),submissionsEnabled:true,languages}];
  if(path==='/api/editor/completions')data=await completion(req.postDataJSON());
  if(path==='/api/runs'&&req.method()==='POST')data={id:'run',status:'FINISHED',verdict:'OK',stdout:Array.from({length:50},(_,i)=>String(i)).join('\n'),language:req.postDataJSON().language};
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await expect(page.getByLabel('Main.java',{exact:true})).toBeVisible();
}
for(const [width,height] of [[1440,900],[1024,650],[390,844]])test(`console stays bounded and scrollable after runs, resizing and case editing ${width}`,async({page})=>{
 await page.setViewportSize({width,height});await workspace(page);
 const console=page.getByRole('region',{name:'실행 결과',exact:true}),body=console.locator('.console-body');
 const initial=await console.boundingBox();expect(initial.height).toBeGreaterThan(60);
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();await expect(console.getByLabel('테스트 6 출력')).toHaveText(/49$/);
 const after=await console.boundingBox();expect(Math.abs(after.y-initial.y)).toBeLessThan(3);expect(Math.abs(after.height-initial.height)).toBeLessThan(3);
 await expect(console).toBeInViewport({ratio:1});expect(await body.evaluate(e=>e.scrollHeight>e.clientHeight&&getComputedStyle(e).overflowY==='auto')).toBe(true);
 await body.evaluate(e=>e.scrollTop=e.scrollHeight);await expect(console.getByLabel('테스트 6 출력')).toBeInViewport();
 const handle=page.getByRole('separator',{name:'편집기와 실행 결과 비율'});await handle.focus();await handle.press('Home');await expect(console).toBeInViewport({ratio:1});await handle.press('End');await expect(console).toBeInViewport({ratio:1});
 await page.getByRole('button',{name:'테스트 케이스 추가',exact:true}).click();
 for(let i=0;i<5;i++)await page.getByRole('button',{name:'+ 케이스 추가',exact:true}).click();
 await expect(page.getByLabel('추가 5 · 입력',{exact:true})).toBeVisible();expect(await body.evaluate(e=>e.scrollHeight>e.clientHeight)).toBe(true);
 await page.getByRole('button',{name:'완료',exact:true}).click();await page.getByRole('button',{name:'코드 실행',exact:true}).click();await expect(console.getByLabel('추가 5 출력')).toHaveText(/49$/);await expect(console).toBeInViewport({ratio:1});
});
test('console font size applies to output and inputs, persists and stays within limits',async({page})=>{
 await workspace(page);
 const console=page.getByRole('region',{name:'실행 결과',exact:true});
 const increase=console.getByRole('button',{name:'터미널 글자 크기 키우기',exact:true});
 const decrease=console.getByRole('button',{name:'터미널 글자 크기 줄이기',exact:true});
 const reset=console.getByRole('button',{name:'터미널 글자 크기 초기화',exact:true});
 await increase.click();await expect(reset).toHaveText('14px');
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 await expect(console.getByLabel('테스트 1 출력')).toHaveCSS('font-size','14px');
 await page.getByRole('button',{name:'테스트 케이스 추가',exact:true}).click();
 await page.getByRole('button',{name:'+ 케이스 추가',exact:true}).click();
 await expect(page.getByLabel('추가 1 · 입력',{exact:true})).toHaveCSS('font-size','14px');
 await page.reload();await expect(reset).toHaveText('14px');
 for(let i=0;i<10;i++)await increase.click();
 await expect(reset).toHaveText('24px');await expect(increase).toBeDisabled();
 for(let i=0;i<14;i++)await decrease.click();
 await expect(reset).toHaveText('10px');await expect(decrease).toBeDisabled();
 await reset.click();await expect(reset).toHaveText('13px');
 await page.reload();await expect(reset).toHaveText('13px');
});
for(const language of ['JAVA','CPP','PYTHON'])test(`semantic members accept Tab with parentheses and retain indentation ${language}`,async({page})=>{
 await workspace(page,()=>({items:[{label:'nextValue()',filterText:'nextValue',kind:2,detail:'String',insertText:'nextValue'}]}));
 await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
 const editor=page.getByLabel(language==='JAVA'?'Main.java':language==='CPP'?'Main.cpp':'Main.py',{exact:true});
 await editor.fill('node.');await editor.press('Control+End');await editor.press('Control+Space');await expect(page.getByRole('option',{name:/nextValue/})).toBeVisible();await page.waitForTimeout(100);
 await editor.press('Tab');await expectCode(editor,'node.nextValue()');await editor.pressSequentially('42');await expectCode(editor,'node.nextValue(42)');
 await editor.press('Escape');await editor.fill('');await editor.press('Tab');await expectCode(editor,'    ');
});
test('late semantic response cannot replace the current draft',async({page})=>{
 await workspace(page,async()=>{await new Promise(r=>setTimeout(r,600));return {items:[{label:'oldMethod',kind:2}]};});
 const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('old.');await editor.press('Control+End');await editor.press('Control+Space');await editor.fill('new draft');await editor.press('Escape');await page.waitForTimeout(800);await expectCode(editor,'new draft');await expect(page.getByRole('option',{name:/oldMethod/})).toHaveCount(0);
});
