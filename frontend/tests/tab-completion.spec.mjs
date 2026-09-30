import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const languages=['JAVA','CPP','PYTHON'].map(id=>({id,label:id}));
async function setup(page,language,vim,diagnostic){
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;
  const question={itemId:'item',problemVersion:'v1',title:'Tab 검증',statement:'테스트',sampleInput:'',sampleOutput:'',languages};
  const session={id:'session',status:'ACTIVE',bankId:'core-a-v1',items:[{id:'item',position:0,category:'implementation',difficulty:'EASY',status:'OPEN',attempts:0,pending:0}],current:question};
  let data=[];
  if(path==='/api/me')data={id:'tab-test',nickname:'Tab'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{...question,version:'v1',submissionsEnabled:true}];
  if(path==='/api/diagnostics')data=[session];
  if(path==='/api/diagnostics/session')data=session;
  if(path==='/api/editor/completions')data={items:[{label:'nextValue()',filterText:'nextValue',kind:2,detail:'method',insertText:'nextValue'}]};
  await route.fulfill({json:data});
 });
 await page.goto(base+(diagnostic?'/#diagnostic':'/#practice'));
 await page.getByLabel(diagnostic?'진단 언어':'풀이 언어',{exact:true}).selectOption(language);
 const e=page.locator(diagnostic?'#diagnostic-source .cm-content':'#source .cm-content');await e.fill('');
 if(vim){await page.evaluate(()=>{localStorage.setItem('gamjaoj-editor-vim','on');window.dispatchEvent(new Event('gamjaoj-editor-settings'));});await expect(page.locator(diagnostic?'#diagnostic-source .cm-vim-panel':'#source .cm-vim-panel')).toBeVisible();await e.click();await e.press('i');}
 return e;
}
for(const diagnostic of [false,true])for(const vim of [false,true])for(const language of ['JAVA','CPP','PYTHON'])test(`Tab immediately accepts ${language}, ${vim?'Vim insert':'normal'}, ${diagnostic?'diagnostic':'practice'}`,async({page})=>{
 const e=await setup(page,language,vim,diagnostic);
 // Keep Date.now fixed while timers run: acceptance must work even at the exact
 // instant the completion opens, without tests hiding the 75 ms guard with sleeps.
 await page.clock.setFixedTime(new Date());
 await e.pressSequentially('node.');await e.press('Control+Space');
 await expect(page.getByRole('option',{name:/nextValue/})).toBeVisible();
 await e.press('Tab');await expectCode(e,'node.nextValue()');
 await e.pressSequentially('42');await expectCode(e,'node.nextValue(42)');
 await e.press('Escape');
 if(vim){await e.press('0');await e.press('x');await expectCode(e,'ode.nextValue(42)');}
});
for(const vim of [false,true])for(const language of ['JAVA','CPP','PYTHON'])test(`completion wins over snippet field navigation ${language} ${vim?'Vim':'normal'}`,async({page})=>{
 const e=await setup(page,language,vim,false);
 await e.pressSequentially('fori');await expect(page.locator('.cm-tooltip-autocomplete [aria-selected="true"]')).toContainText('코드 템플릿');await page.waitForTimeout(100);await e.press('Tab');
 // First field has a following field. Member completion here must not jump to it.
 await e.pressSequentially('node.');await e.press('Control+Space');await expect(page.getByRole('option',{name:/nextValue/})).toBeVisible();await page.waitForTimeout(100);
 await e.press('Tab');await expect(e).toContainText('node.nextValue()');
});
