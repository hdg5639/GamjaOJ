import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test('confirmed feedback becomes a linked training and explicit reflection at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let goals=[],sessions=[],startCalls=0,reflectionCalls=0,generationCalls=0,repeatCalls=0;
  const source={id:'old',problemVersion:'v1',source:'class Main {}',status:'FINISHED',verdict:'WA',createdAt:'2026-09-24T00:00:00Z',input:null};
  const analysis={id:'analysis',submissionId:'old',kind:'ANALYSIS',status:'COMPLETED',result:{summary:'누적값 범위를 확인하세요.',observations:[],nextSteps:['합계에 long을 사용하기'],uncertainty:'다른 원인은 확인되지 않음'}};
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=['v1','v2'].map(version=>({version,title:version==='v1'?'원래 문제':'다음 문제',statement:'합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}));
    if(path==='/api/submissions')data=[source];
    if(path==='/api/submissions/old')data=source;
    if(path==='/api/ai/tasks')data=[analysis];
    if(path==='/api/practice-followups/options')data={steps:analysis.result.nextSteps,focuses:[{id:'overflow',label:'정수 범위·오버플로'}],type:'수열 합'};
    if(path==='/api/practice-followups'){
      if(req.method()==='POST'){
        expect(req.postDataJSON()).toEqual({analysisId:'analysis',stepIndex:0,focus:'overflow'});
        goals=[{id:'goal',goal:'합계에 long을 사용하기',status:'READY_TO_PRACTICE',candidates:[{version:'v2',title:'다음 문제',statement:'합계 범위를 연습하는 문제'}]}];data=goals[0];
      }else data=goals;
    }
    if(path==='/api/practice-followups/goal/start'){
      startCalls++;expect(req.postDataJSON()).toEqual({problemVersion:'v2',round:1});
      sessions=[{id:'goal',problemVersion:'v2',goal:'합계에 long을 사용하기',status:'ACTIVE',startedAt:'2026-09-24T00:00:00Z',pending:0,submissions:0,accepted:0,runs:0}];
      goals=[{...goals[0],status:'ACTIVE',sessionId:'goal',problemVersion:'v2',candidates:[]}];data=goals[0];
      if(startCalls===1)return route.abort(); // accepted response lost; retry must reopen the same session
    }
    if(path==='/api/training-sessions')data=sessions;
    if(path==='/api/training-sessions/goal')data={session:sessions[0],entries:[]};
    if(path==='/api/training-sessions/goal/end'){
      sessions=[{...sessions[0],status:'ENDED',accepted:1,submissions:1,endedAt:'2026-09-24T00:01:00Z'}];
      goals=[{...goals[0],status:'AWAITING_REFLECTION'}];data=sessions[0];
    }
    if(path==='/api/practice-followups/goal/reflect'){
      reflectionCalls++;expect(req.postDataJSON()).toEqual({usedHelp:false,round:1});goals=[{...goals[0],status:'SELF_REPORTED_UNASSISTED_AC',usedHelp:false,reviewedSubmissionId:'new'}];data=goals[0];
    }
    if(path==='/api/practice-followups/goal/repeat'){
      repeatCalls++;expect(req.postDataJSON()).toEqual({round:1});
      goals=[{...goals[0],round:2,status:'READY_TO_PRACTICE',sessionId:null,candidates:[],attempts:[{round:1,sessionId:'goal',problemVersion:'v2',status:'SELF_REPORTED_UNASSISTED_AC'}]}];data=goals[0];
      if(repeatCalls===1)return route.abort();
    }
    if(path.endsWith('/generate'))generationCalls++;
    await route.fulfill({json:data});
  });
  await page.goto(base+'/#practice');
  await page.getByRole('button',{name:'제출 기록',exact:true}).click();
  await page.getByText('최근 제출 내역',{exact:false}).click();
  await page.locator('#submission-results .record-list li button').click();
  await page.getByRole('button',{name:'이 제출 피드백 보기'}).click();
  await page.getByText('이 분석으로 이어서 연습',{exact:true}).click();
  await page.getByRole('button',{name:'이 분석으로 다음 훈련 준비'}).click();
  await expect(page.getByLabel('다시 연습할 지점')).toHaveValue('0');
  await page.getByRole('button',{name:'이 목표 확인하고 다음 훈련 찾기'}).click();
  const panel=page.getByRole('region',{name:'다음 훈련',exact:true});
  await page.getByText('제출 피드백으로 만든 연습 목표',{exact:true}).click();
  await expect(panel).toBeVisible();
  await panel.getByText('문제 내용 확인',{exact:true}).click();
  await expect(panel.getByText('합계 범위를 연습하는 문제',{exact:true})).toBeVisible();
  expect(startCalls).toBe(0);expect(generationCalls).toBe(0);
  await panel.getByRole('button',{name:'이 문제로 훈련'}).click();
  await expect(panel.getByRole('alert')).toBeVisible();
  await panel.getByRole('button',{name:'이 문제로 훈련'}).click();
  if(width<800)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
  await expect(page.getByLabel('풀이할 문제')).toHaveValue('v2');
  await expect(page.locator('.workspace-heading')).toContainText('훈련 중');
  expect(sessions).toHaveLength(1);
  await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
  await page.getByRole('button',{name:'훈련 마무리',exact:true}).click();
  await page.getByRole('button',{name:'훈련 마치기',exact:true}).click();
  await panel.getByRole('button',{name:'도움 없이 해결했어요'}).click();
  await expect(panel.locator('summary')).toContainText('도움 없이 정답 해결 · 본인 확인');
  expect(reflectionCalls).toBe(1);expect(generationCalls).toBe(0);
  await page.reload();await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
  await page.getByText('제출 피드백으로 만든 연습 목표',{exact:true}).click();
  await expect(panel.locator('summary')).toContainText('도움 없이 정답 해결 · 본인 확인');
  await panel.getByRole('button',{name:'같은 목표로 다시 연습'}).click();
  await expect(panel.getByRole('alert')).toBeVisible();
  await panel.getByRole('button',{name:'같은 목표로 다시 연습'}).click();
  await expect(panel.locator('summary').first()).toContainText('2차');
  await expect(panel.getByRole('button',{name:'1차 훈련 기록 보기'})).toBeHidden();
  await panel.getByText('이전 시도 (1)',{exact:true}).click();
  await panel.getByRole('button',{name:'1차 훈련 기록 보기'}).click();
  await expect(page.locator('#training-detail-heading')).toBeFocused();
  await page.reload();await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
  await page.getByText('제출 피드백으로 만든 연습 목표',{exact:true}).click();
  await expect(panel.locator('summary').first()).toContainText('2차');
  expect(repeatCalls).toBe(2);expect(generationCalls).toBe(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:`/tmp/gamja-followup-${width}.png`,fullPage:true});
});
