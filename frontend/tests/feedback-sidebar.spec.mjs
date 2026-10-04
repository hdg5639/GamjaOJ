import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
for(const width of [390,1024,1440])test(`feedback prioritizes latest result and preserves expanded tools ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});
 const submission={id:'accepted',problemVersion:'feedback-test',source:'class Main {}',language:'JAVA',status:'FINISHED',verdict:'AC',createdAt:'2026-10-04T00:00:00Z',input:null};
 const result={summary:'누적값의 범위를 확인했어요. '.repeat(30),observations:['코드 근거를 길게 살펴봅니다. '.repeat(40)],nextSteps:['정수 범위를 연습해 보세요.'],uncertainty:'이 코드만으로 숙련도를 확정하지 않아요.'};
 let tasks=Array.from({length:12},(_,i)=>({id:'analysis-'+i,kind:'ANALYSIS',status:'COMPLETED',model:'test-model',effort:'low',createdAt:'2026-10-04T00:00:00Z',result})),posts=0,retries=0,reflection={note:'',confidence:null};
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'learner',nickname:'테스트'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'feedback-test',title:'피드백 테스트',statement:'두 수를 더합니다.',submissionsEnabled:true}];
  if(path==='/api/submissions')data=[submission];
  if(path==='/api/submissions/accepted')data=submission;
  if(path==='/api/ai/status')data={operator:false};
  if(path==='/api/ai/tasks'){
   if(req.method()==='POST'){posts++;expect(req.postDataJSON()).toMatchObject({submissionId:'accepted',kind:'HINT',question:'내 질문 초안'});tasks=[{id:'hint-new',kind:'HINT',status:'UNKNOWN',errorCode:'TIMEOUT'},...tasks];}
   data=tasks;
  }
  if(path==='/api/ai/tasks/hint-new/retry'){retries++;tasks=[{...tasks[0],status:'QUEUED'},...tasks.slice(1)];data=tasks[0];}
  if(path==='/api/my/reflections'){
   if(req.method()==='PUT')reflection={...reflection,...req.postDataJSON()};data=reflection;
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');
 const editor=page.getByLabel('Main.java',{exact:true});await expect(editor).toBeVisible();await editor.fill('// keep draft');
 await page.getByRole('button',{name:'제출 기록',exact:true}).click();
 await page.getByText('최근 제출 내역',{exact:false}).click();
 await page.locator('#submission-results .record-list li button').click();
 await page.getByRole('button',{name:'이 제출 피드백 보기'}).click();
 const feedback=page.getByRole('region',{name:'개인 학습 피드백'});
 await expect(feedback.locator('.feedback-summary').first()).toBeVisible();
 await expect(feedback.locator('.feedback-history > summary')).toHaveText('지난 분석·힌트 (11)');
 await expect(feedback.getByLabel('추가로 궁금한 점')).toBeHidden();
 await expect(feedback.getByRole('button',{name:'확실히 풀 수 있음',exact:true})).toBeHidden();
 await expect(feedback.getByText(result.observations[0],{exact:true}).first()).toBeHidden();
 expect((await feedback.boundingBox()).height).toBeLessThan(650);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 expect(posts).toBe(0);
 await page.screenshot({path:`/tmp/gamja-feedback-${width}.png`});
 await page.evaluate(()=>document.documentElement.dataset.theme='dark');
 await expect(feedback).toBeVisible();
 await page.screenshot({path:`/tmp/gamja-feedback-dark-${width}.png`});
 await page.evaluate(()=>document.documentElement.dataset.theme='light');
 await feedback.locator('.feedback-analysis-detail > summary').first().focus();await page.keyboard.press('Enter');
 await expect(feedback.getByText(result.observations[0],{exact:true}).first()).toBeVisible();
 await feedback.locator('.feedback-analysis-detail > summary').first().press('Enter');
 const questionToggle=feedback.locator('.feedback-question > summary');await questionToggle.click();
 await feedback.getByLabel('추가로 궁금한 점').fill('내 질문 초안');await questionToggle.click();await questionToggle.click();
 await expect(feedback.getByLabel('추가로 궁금한 점')).toHaveValue('내 질문 초안');
 await feedback.locator('.feedback-reflection > summary').click();
 await feedback.getByRole('button',{name:'확실히 풀 수 있음',exact:true}).click();
 await feedback.getByLabel('다음에 볼 짧은 메모').fill('회고 초안');
 await feedback.locator('.feedback-reflection > summary').click();await feedback.locator('.feedback-reflection > summary').click();
 await expect(feedback.getByLabel('다음에 볼 짧은 메모')).toHaveValue('회고 초안');
 await feedback.getByRole('button',{name:'맞춤 힌트 요청',exact:true}).click();
 await expect(feedback.getByRole('button',{name:'추가 비용으로 재시도'})).toBeVisible();expect(posts).toBe(1);
 await feedback.getByRole('button',{name:'추가 비용으로 재시도'}).click();
 await expect(feedback.locator('.feedback-entry > h4').first()).toHaveText('맞춤 힌트 · 분석 대기');expect(retries).toBe(1);
 await expect(feedback.getByLabel('추가로 궁금한 점')).toHaveValue('내 질문 초안');
 await feedback.locator('.feedback-history > summary').click();await feedback.locator('.feedback-past-entry > summary').first().click();
 await expect(feedback.locator('.feedback-past-entry .feedback-summary').first()).toBeVisible();
 if(width<800){await page.getByRole('button',{name:'결과 접기',exact:true}).click();await page.getByRole('button',{name:'코드 작성',exact:true}).click();}
 await expect(editor).toContainText('// keep draft');
 await page.evaluate(()=>document.documentElement.dataset.theme='dark');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
