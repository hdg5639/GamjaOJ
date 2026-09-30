import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const mode of ['MULTI_API','SINGLE_FUNCTION'])for(const width of [390,1440])test(`callable template, driver, reset and submission ${mode} ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});const writes=[];
 const name=mode==='MULTI_API'?'init':'solution',returns=mode==='MULTI_API'?'void':'int';
 const template=`public class UserSolution { public ${returns} ${name}(int n) {${returns==='int'?'return 0;':''}} }`;
 const driver='public class Main { /* fixed server driver */ }';
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'callable-user',nickname:'테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'api-problem',title:'API 연습',statement:'메서드를 완성하세요.',sampleInput:`[[["${name}",1]]]`,sampleOutput:'0',submissionsEnabled:true,languages:[{id:'JAVA',label:'Java 8'}],api:{format:'JAVA_CALLABLE_V1',sourceFile:'UserSolution.java',template,driver,api:{mode,methods:[{name,returns,parameters:[{name:'n',type:'int'}],description:'주어진 값으로 처리합니다.'}]}}}];
  if(path==='/api/runs'&&req.method()==='POST'){writes.push(req.postDataJSON());data={id:'run',status:'FINISHED',verdict:'OK',stdout:'0',language:'JAVA'};}
  if(path==='/api/submissions'&&req.method()==='POST'){writes.push(req.postDataJSON());data={id:'submission',status:'FINISHED',verdict:'AC',problemVersion:'api-problem',language:'JAVA',createdAt:new Date().toISOString()};}
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');const editor=page.getByLabel('UserSolution.java',{exact:true});await expect(editor).toBeVisible();await expectCode(editor,template);
 await expect(page.getByLabel('풀이 언어',{exact:true}).locator('option')).toHaveCount(1);
 await page.getByRole('button',{name:'문제 보기',exact:true}).click();
 await page.getByText('Main.java · 읽기 전용',{exact:true}).click();await expect(page.locator('.problem-card pre').filter({hasText:'fixed server driver'})).toBeVisible();
 if(width===390)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
 const changed=template.replace('public class','// draft\npublic class');await editor.fill(changed);
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();await expect.poll(()=>writes.length).toBe(1);
 expect(writes[0].source).toBe(changed);expect(writes[0].language).toBe('JAVA');expect(writes[0].driver).toBeUndefined();
 await page.getByRole('button',{name:'제출 후 채점하기',exact:true}).click();await expect.poll(()=>writes.length).toBe(2);expect(writes[1].source).toBe(changed);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`/tmp/gamjaoj-callable-${mode}-${width}.png`});
 await page.reload();await expectCode(page.getByLabel('UserSolution.java',{exact:true}),changed);
 await page.getByRole('button',{name:'코드 초기화',exact:true}).click();
 await page.getByRole('button',{name:'초기화',exact:true}).click();await expectCode(page.getByLabel('UserSolution.java',{exact:true}),template);
});
