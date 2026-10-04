import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
import {readFileSync} from 'node:fs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
const bundles=Object.fromEntries(['JAVA','CPP','PYTHON'].map(l=>[l,JSON.parse(readFileSync(new URL(`../../backend/target/native-callable-fixtures/single-${l}.json`,import.meta.url)))]));
const languages=[{id:'JAVA',label:'Java 8'},{id:'CPP',label:'C++17'},{id:'PYTHON',label:'Python 3.12'}];
for(const diagnostic of [false,true])test(`callable language templates, draft isolation and run payload ${diagnostic?'diagnostic':'workspace'}`,async({page})=>{
 const bundle={...bundles.JAVA,languages:bundles};const problem={version:'lang-api',problemVersion:'lang-api',itemId:'item',title:'언어별 호출',statement:'solution의 결과를 반환합니다.',api:bundle,languages,submissionsEnabled:true,examples:[{input:'[[["solution"]]]',output:'0'}]};
 const session={id:'session',status:'ACTIVE',items:[{id:'item',position:0,category:'implementation',difficulty:'CORE',status:'OPEN',attempts:0,pending:0}],current:problem};let runs=[];
 await page.route('**/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'langs-'+diagnostic,nickname:'언어검증'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[problem];
  if(path==='/api/diagnostics')data=[session];if(path==='/api/diagnostics/session')data=session;
  if(path==='/api/runs'){runs.push(request.postDataJSON());data={id:'run-'+runs.length,status:'FINISHED',verdict:'OK',stdout:'0',language:runs.at(-1).language};}
  await route.fulfill({json:data});
 });
 await page.goto(base+(diagnostic?'/#diagnostic':'/#practice'));
 const choice=page.getByLabel(diagnostic?'진단 언어':'풀이 언어',{exact:true});await expect(choice).toBeVisible();
 for(const l of ['CPP','PYTHON','JAVA','CPP']){
  await choice.selectOption(l);
  const editor=page.getByLabel(diagnostic?`진단 ${l==='JAVA'?'Java':l==='CPP'?'C++17':'Python 3.12'} 코드`:bundles[l].sourceFile,{exact:true});
  await expect(editor).toBeVisible();
  if(l==='CPP'&&runs.length)await expectCode(editor,bundles[l].template+'\n// saved');
  else await expectCode(editor,bundles[l].template);
  if(l==='CPP'&&!runs.length)await editor.fill(bundles[l].template+'\n// saved');
  await page.getByRole('button',{name:'코드 실행',exact:true}).click();await expect.poll(()=>runs.at(-1)?.language).toBe(l);
  await expect(page.locator('.console-outcome').last()).toHaveText('테스트를 통과하였습니다.');
 }
 expect(runs.map(r=>r.language)).toEqual(['CPP','PYTHON','JAVA','CPP']);
});
