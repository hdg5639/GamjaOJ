import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
const definition={mode:'MULTI_API',methods:[
 {name:'init',returns:'void',parameters:[{name:'n',type:'int'}],description:'초기 상태를 설정합니다.'},
 {name:'put',returns:'void',parameters:[{name:'v',type:'long'}],description:'값을 저장합니다.'},
 {name:'query',returns:'String',parameters:[{name:'key',type:'String'}],description:'문자열을 반환합니다.'}
]};
const sample='[[["init",2],["put",9223372036854775807],["query","a b"]]]';
for(const width of [390,1024,1440])test(`callable test editing, precision, files and execution ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});let runs=[];
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'callable-case',nickname:'테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'case-api',title:'호출 테스트',statement:'구현한 함수를 순서대로 호출합니다.',submissionsEnabled:true,api:{api:definition,sourceFile:'UserSolution.java',template:'public class UserSolution {}',driver:'read-only driver'},examples:[{input:sample,output:'"a b"'}],languages:[{id:'JAVA',label:'Java 8'}]}];
  if(path==='/api/runs'){
   const body=req.postDataJSON();runs.push(body);
   const inputs=body.inputs||[body.input];
   data={id:'run-'+runs.length,status:'FINISHED',verdict:'OK',language:'JAVA',runCases:inputs.map((input,i)=>({number:i+1,verdict:'OK',stdout:input.includes('"one"')?'"one"\n"two"':'"a\\u0020b"',stderr:'',outputTruncated:false}))};
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await expect(page.getByLabel('UserSolution.java',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 await page.getByRole('button',{name:'예제 1 호출·기댓값으로 추가',exact:true}).click();
 const console=page.locator('.run-console:visible');
 await expect(console.getByLabel('추가 1 · 호출 2 · v')).toHaveValue('9223372036854775807');
 await expect(console.getByLabel('추가 1 · 호출 3 기댓값')).toHaveValue('a b');
 await page.screenshot({path:`/tmp/gamja-callable-typed-${width}.png`});
 await page.evaluate(()=>document.documentElement.dataset.theme='dark');
 await page.screenshot({path:`/tmp/gamja-callable-dark-${width}.png`});
 await page.evaluate(()=>document.documentElement.dataset.theme='light');
 await page.keyboard.press('Escape');
 await expect(page.getByRole('dialog',{name:'명령 처리 테스트 케이스'})).toBeHidden();
 await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 await expect(console.getByLabel('추가 1 · 호출 2 · v')).toHaveValue('9223372036854775807');
 await console.getByLabel('추가 1 · 호출 2 · v').fill('9223372036854775808');
 await expect(console.getByRole('alert')).toContainText('long의 정수 범위');
 await page.getByRole('button',{name:'저장하고 실행',exact:true}).click();expect(runs).toHaveLength(0);
 await console.getByLabel('추가 1 · 호출 2 · v').fill('9223372036854775807');
 await page.reload();await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 await expect(console.getByLabel('추가 1 · 호출 2 · v')).toHaveValue('9223372036854775807');
 await console.getByRole('button',{name:'완료',exact:true}).click();
 await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 await expect(console.locator('.console-outcome').last()).toHaveText('테스트를 통과하였습니다.');
 expect(runs).toHaveLength(1);expect(runs[0].inputs).toEqual([sample,sample]);
 await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 await console.getByText('입력·출력 파일 불러오기',{exact:true}).click();
 const input='[[["init",1],["query","one"]],[["init",2],["query","two"]]]';
 await console.getByLabel('추가 1 입력 파일').setInputFiles({name:'input.txt',mimeType:'text/plain',buffer:Buffer.from(input)});
 await console.getByLabel('추가 1 기댓값 파일').setInputFiles({name:'output.txt',mimeType:'text/plain',buffer:Buffer.from('"one"\n"two"')});
 await expect(console.getByLabel('추가 1 · 입력')).toHaveValue(input);
 await console.getByRole('button',{name:'호출 편집기로 전환'}).click();
 await expect(console.getByRole('alert')).toContainText('여러 독립 테스트');
 await expect(console.getByLabel('추가 1 · 입력')).toHaveValue(input);
 await console.getByRole('button',{name:'완료',exact:true}).click();await page.getByRole('button',{name:'코드 실행',exact:true}).click();
 await expect(console.locator('.console-outcome').last()).toHaveText('테스트를 통과하였습니다.');
 await expect.poll(()=>runs.length).toBe(2);expect(runs[1].inputs).toEqual([sample,input]);
 await expect(console.getByLabel('추가 1 출력')).toHaveText('"one"\n"two"');
 await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-callable-cases-${width}.png`});
});
test('diagnostic single-function uses the same typed editor and never adds a second call',async({page})=>{
 const api={mode:'SINGLE_FUNCTION',methods:[{name:'solution',returns:'int[]',parameters:[{name:'values',type:'int[]'}],description:'배열 결과를 반환합니다.'}]};
 const current={itemId:'item',problemVersion:'single',title:'단일 함수',statement:'배열을 처리합니다.',api:{api,sourceFile:'UserSolution.java',template:'public class UserSolution {}',driver:'driver'},languages:[{id:'JAVA',label:'Java 8'}],examples:[{input:'[[["solution",[1,2]]]]',output:'[1,2]'}]};
 const session={id:'session',status:'ACTIVE',items:[{id:'item',position:0,category:'implementation',difficulty:'CORE',status:'OPEN',attempts:0,pending:0}],current};
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'single-learner',nickname:'테스트'};
  if(path==='/api/diagnostics')data=[session];if(path==='/api/diagnostics/session')data=session;
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#diagnostic');await expect(page.getByLabel('진단 Java 코드',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:/^테스트 케이스 추가/}).click();
 await page.getByRole('button',{name:'예제 1 호출·기댓값으로 추가',exact:true}).click();
 await expect(page.getByLabel('추가 1 · 호출 1 · values')).toHaveValue('[1,2]');
 await expect(page.getByRole('button',{name:'+ 함수 호출 추가',exact:true})).toHaveCount(0);
 await expect(page.getByLabel('추가 1 · 호출 1 함수')).toBeDisabled();
});
