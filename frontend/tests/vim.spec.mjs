import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
const mod=process.platform==='darwin'?'Meta':'Control';
const languages=[{id:'JAVA',label:'Java 8',timeLimitMs:5000,memoryMb:384},{id:'CPP',label:'C++17',timeLimitMs:3000,memoryMb:256},{id:'PYTHON',label:'Python 3.12',timeLimitMs:8000,memoryMb:256}];
async function setup(page,diagnostic=false) {
  const calls={runs:[],submissions:[],drafts:[]};
  const question={itemId:'item1',problemVersion:'v1',title:'Vim 검증',statement:'합',sampleInput:'1 2',sampleOutput:'3',languages};
  const session={id:'session',status:'ACTIVE',bankId:'core-a-v1',items:[{id:'item1',position:0,category:'implementation',difficulty:'EASY',status:'OPEN',attempts:0,pending:0}],current:question};
  await page.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'vim-user',username:'vim-user',nickname:'Vim'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
    if(path==='/api/problems')data=[{version:'v1',title:'Vim 검증',statement:'합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true,languages}];
    if(path==='/api/diagnostics')data=[session];
    if(path==='/api/diagnostics/session')data=session;
    if(path.includes('/drafts')&&r.method()!=='GET'){calls.drafts.push(r.postDataJSON());data={};}
    if(path==='/api/runs'&&r.method()==='POST'){
      const body=r.postDataJSON();calls.runs.push(body);data={id:'run'+calls.runs.length,status:'FINISHED',verdict:'OK',input:body.input,source:body.source,stdout:'3',tests:[],language:body.language};
    }
    if(path==='/api/submissions'&&r.method()==='POST'){
      const body=r.postDataJSON();calls.submissions.push(body);data={id:'submission',status:'FINISHED',verdict:'WA',source:body.source,problemVersion:'v1',language:body.language,diagnosticItemId:body.diagnosticItemId,tests:[]};
    }
    await route.fulfill({json:data});
  });
  await page.goto(base+(diagnostic?'/#diagnostic':'/#practice'));
  const editor=page.locator(diagnostic?'#diagnostic-source .cm-content':'#source .cm-content');
  await expect(editor).toBeVisible();
  return {calls,editor,session};
}
async function toggle(page,diagnostic=false){
  const tools=page.locator(diagnostic?'#diagnostic-editor-tools > summary':'#editor-tools > summary');
  await tools.click();await page.locator(diagnostic?'#diagnostic-editor-tools':'#editor-tools').getByRole('checkbox').check();await tools.click();
}
async function ex(page,editor,command){await editor.click();await page.keyboard.press('Escape');await page.keyboard.type(':'+command);await page.keyboard.press('Enter');}
for(const diagnostic of [false,true])test(`Vim save/run/submit reaches current editor and language (${diagnostic?'diagnostic':'practice'})`,async({page})=>{
  const {calls,editor}=await setup(page,diagnostic);
  await page.getByLabel(diagnostic?'진단 언어':'풀이 언어',{exact:true}).selectOption('PYTHON');
  await editor.fill('print(3)');await toggle(page,diagnostic);
  await expect.poll(()=>calls.drafts.length).toBeGreaterThan(0);
  const priorDrafts=calls.drafts.length;
  await ex(page,editor,'w');
  await expect.poll(()=>calls.drafts.length).toBeGreaterThan(priorDrafts);
  expect(calls.submissions).toHaveLength(0);expect(calls.runs).toHaveLength(0);
  await expect.poll(()=>calls.drafts.length).toBeGreaterThan(0);
  await ex(page,editor,'run unexpected');
  await expect(page.getByText(':run는 인수나 범위 없이 사용해 주세요.',{exact:true})).toBeVisible();
  expect(calls.runs).toHaveLength(0);
  await ex(page,editor,'run');await expect.poll(()=>calls.runs.length).toBe(1);
  expect(calls.runs[0].source).toBe('print(3)');expect(calls.runs[0].language).toBe('PYTHON');
  expect(calls.submissions).toHaveLength(0);
  await editor.click();await page.keyboard.type('i');await page.keyboard.press('F5');await expect.poll(()=>calls.runs.length).toBe(2);
  await page.keyboard.press(`${mod}+Shift+Enter`);await expect.poll(()=>calls.runs.length).toBe(3);
  await ex(page,editor,'submit');await expect.poll(()=>calls.submissions.length).toBe(1);
  expect(calls.submissions[0].source).toBe('print(3)');expect(calls.submissions[0].language).toBe('PYTHON');
  if(diagnostic)expect(calls.submissions[0].diagnosticItemId).toBe('item1');
});
test('Vim text objects, registers, macros, search/replace and undo work in the real editor',async({page})=>{
  const {editor}=await setup(page);await editor.fill('one two\nthree four');await toggle(page);
  await editor.click();await page.keyboard.press('Escape');await page.keyboard.type('gg0ciwONE');await page.keyboard.press('Escape');
  await expectCode(editor,'ONE two\nthree four');
  // CodeMirror's history records deletion and replacement typing separately.
  await page.keyboard.type('uu');await expectCode(editor,'one two\nthree four');
  await page.keyboard.press('Control+r');await page.keyboard.press('Control+r');await expectCode(editor,'ONE two\nthree four');
  await ex(page,editor,'%s/two/TWO/g');await expectCode(editor,'ONE TWO\nthree four');
  await page.keyboard.type('gg0"ayyG"ap');await expectCode(editor,'ONE TWO\nthree four\nONE TWO');
  await page.keyboard.type('gg0qaA!');await page.keyboard.press('Escape');await page.keyboard.type('qj@a');
  await expectCode(editor,'ONE TWO!\nthree four!\nONE TWO');
  await page.keyboard.type('gg0vll');await page.keyboard.type('d');await expectCode(editor,' TWO!\nthree four!\nONE TWO');
  await page.keyboard.type('u');await expectCode(editor,'ONE TWO!\nthree four!\nONE TWO');
  await page.keyboard.type('gg02lrxj0.');await expectCode(editor,'ONx TWO!\nxhree four!\nONE TWO');
  await page.keyboard.type('/four');await page.keyboard.press('Enter');await page.keyboard.type('ciwFOUR');await page.keyboard.press('Escape');
  await expectCode(editor,'ONx TWO!\nxhree FOUR!\nONE TWO');
});

for(const language of ['JAVA','CPP','PYTHON'])test(`Application shortcuts also work without Vim (${language})`,async({page})=>{
  const {calls,editor}=await setup(page);
  await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
  await editor.fill('// current '+language);
  await page.keyboard.press(`${mod}+s`);
  await expect.poll(()=>calls.drafts.length).toBeGreaterThan(0);
  await page.keyboard.press('F5');await expect.poll(()=>calls.runs.length).toBe(1);
  await page.keyboard.press(`${mod}+Enter`);await expect.poll(()=>calls.submissions.length).toBe(1);
  expect(calls.runs[0].source).toBe('// current '+language);
  expect(calls.submissions[0].language).toBe(language);
});
test('Paused diagnostic does not execute or submit from editor shortcuts',async({page})=>{
  const {calls,editor,session}=await setup(page,true);
  await editor.fill('print(3)');await toggle(page,true);
  session.status='PAUSED';
  await expect(editor).toHaveAttribute('contenteditable','false');
  await editor.click();await page.keyboard.press('F5');await page.keyboard.press(`${mod}+Enter`);
  await ex(page,editor,'run');await ex(page,editor,'submit');
  expect(calls.runs).toHaveLength(0);expect(calls.submissions).toHaveLength(0);
});
for(const diagnostic of [false,true])test(`Shortcut help fits a narrow editor (${diagnostic?'diagnostic':'practice'})`,async({page})=>{
  await page.setViewportSize({width:390,height:700});await setup(page,diagnostic);
  const tools=page.locator(diagnostic?'#diagnostic-editor-tools':'#editor-tools');
  await tools.locator('summary').click();
  const panel=tools.locator('.tool-pop-panel');await expect(panel).toBeVisible();
  await expect(tools).toHaveAttribute('style',/--tool-panel-height/);
  const box=await panel.boundingBox();expect(box.x).toBeGreaterThanOrEqual(0);expect(box.x+box.width).toBeLessThanOrEqual(391);
  expect(box.y+box.height).toBeLessThanOrEqual(700);
  await page.screenshot({path:`/tmp/gamjaoj-vim-help-${diagnostic?'diagnostic':'practice'}.png`,fullPage:true});
});
