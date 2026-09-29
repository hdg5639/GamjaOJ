import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const languages=[{id:'JAVA',label:'Java 8',timeLimitMs:5000,memoryMb:384},{id:'CPP',label:'C++17 · GCC 13',timeLimitMs:3000,memoryMb:256},{id:'PYTHON',label:'Python 3.12',timeLimitMs:8000,memoryMb:256}];
for(const width of [390,1440])test('language drafts, custom execution and retry retain language at '+width,async({page})=>{
 await page.setViewportSize({width,height:900});let calls=[],runs=[];
 await page.route('**/api/**',async route=>{
  const r=route.request(),path=new URL(r.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'languages',username:'languages',nickname:'언어 테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'v1',title:'언어 테스트',statement:'두 수의 합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true,languages}];
  if(path==='/api/runs'&&r.method()==='POST'){runs.push(r.postDataJSON());data={id:'run',status:'FINISHED',verdict:'OK',input:'1 2',source:r.postDataJSON().source,problemVersion:'v1',createdAt:'2026-09-26T00:00:00Z',stdout:'3',execution:languages.find(l=>l.id===r.postDataJSON().language),language:r.postDataJSON().language,tests:[]};}
  if(path==='/api/submissions'&&r.method()==='POST'){
   calls.push({body:r.postDataJSON(),key:r.headers()['idempotency-key']});
   if(calls.length===1)return route.abort();
   data={id:'saved',status:'FINISHED',verdict:'AC',language:calls[0].body.language,problemVersion:'v1',source:calls[0].body.source,tests:[]};
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');
 await page.getByLabel('Main.java',{exact:true}).fill('// Java draft');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('CPP');
 await expect(page.getByText('테스트당 3초 (시작 포함)',{exact:false})).toBeVisible();
 await page.getByLabel('Main.cpp',{exact:true}).fill('// C++ draft');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('PYTHON');
 await page.getByLabel('Main.py',{exact:true}).fill('print(3)');
 await page.reload();
 await expectCode(page.getByLabel('Main.py',{exact:true}),'print(3)');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('CPP');
 await expectCode(page.getByLabel('Main.cpp',{exact:true}),'// C++ draft');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('JAVA');
 await expectCode(page.getByLabel('Main.java',{exact:true}),'// Java draft');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('PYTHON');
 await page.getByRole('button',{name:'입력 직접 넣기',exact:true}).click();
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 await expect.poll(()=>runs.length).toBe(1);expect(runs[0].language).toBe('PYTHON');expect(runs[0].source).toBe('print(3)');
 await expect(page.getByRole('button',{name:'해당 실행 코드 보기',exact:true})).toHaveCount(0);
 await expect(page.getByLabel('실행 표준 출력')).toHaveText('3');
 if(width===390)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
 await page.screenshot({path:`/tmp/gamja-languages-editor-${width}.png`,fullPage:true});
 await page.getByRole('button',{name:'제출 후 채점하기',exact:true}).click();
 await expect.poll(()=>calls.length).toBe(1);
 await expect(page.getByLabel('풀이 언어',{exact:true})).toBeDisabled();
 await page.reload();
 await expectCode(page.getByLabel('Main.py',{exact:true}),'print(3)');
 await page.getByRole('button',{name:/같은 제출/}).click();
 await expect.poll(()=>calls.length).toBe(2);expect(calls[1]).toEqual(calls[0]);expect(calls[1].body.language).toBe('PYTHON');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-languages-${width}.png`,fullPage:true});
});

for(const language of ['CPP','PYTHON'])for(const width of [390,1440])test(`${language} completion preserves explicit acceptance at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  await route.fulfill({json:path==='/api/me'?{id:'completion',nickname:'완성'}:path==='/api/problems'?[{version:'v1',title:'완성 테스트',statement:'테스트',sampleInput:'',sampleOutput:'',submissionsEnabled:true,languages}]:[]});
 });
 await page.goto(base+'/#practice');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
 const editor=page.getByLabel(language==='CPP'?'Main.cpp':'Main.py',{exact:true});
 const prefix=language==='CPP'?'int totalCount = 0;\nint solveValue(int number) { return number; }\n':'totalCount = 0\ndef solveValue(number):\n    return number\n';
 async function suggest(text,label){
  await editor.fill(text);await editor.press('Control+End');await editor.press('Control+Space');
  await expect(page.getByRole('option',{name:label,exact:false})).toBeVisible();
  await page.waitForTimeout(100); // CodeMirror protects against accepting immediately after opening.
 }
 await suggest(prefix+'totalC','totalCount');await editor.press('Enter');await expectCode(editor,prefix+'totalCount');
 await suggest(prefix+'solveV','solveValue');await editor.press('Enter');await expectCode(editor,prefix+'solveValue');
 const short=language==='CPP'?'priority_q':'defaul';const full=language==='CPP'?'priority_queue':'defaultdict';
 await editor.fill('');await editor.pressSequentially(short);await expect(page.getByRole('option',{name:full,exact:false})).toBeVisible();await editor.press('Escape');
 await suggest(short,full);await editor.press('Space');await expectCode(editor,short+' ');
 await suggest(short,full);await editor.press('Tab');expect(await editor.innerText()).not.toContain(full);
 await suggest(short,full);await editor.press('Escape');await expect(page.getByRole('listbox')).toBeHidden();
 await suggest(short,full);await editor.press('Enter');await expectCode(editor,full);
 await suggest(language==='CPP'?'values.push_b':'values.appe',language==='CPP'?'push_back':'append');
 await editor.press('Enter');await expectCode(editor,language==='CPP'?'values.push_back':'values.append');
 for(const text of [language==='CPP'?'// priority_q':'# defaul',language==='CPP'?'"priority_q':'"defaul']){
  await editor.fill(text);await editor.press('Control+End');await editor.press('Control+Space');await expect(page.getByRole('listbox')).toBeHidden();
 }
 await suggest(short,full);await page.screenshot({path:`/tmp/gamja-completion-${language}-${width}.png`});
});
