import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test('optional diagnostic survives retry and advances at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let session=null,keys=[],submission=null,evaluations=[],correctionKeys=[],plans=[],training=[],planKeys=[],generationCalls=0,orderCalls=0;
  const q=n=>({itemId:'item'+n,problemVersion:'v'+n,title:'진단 문항 '+n,statement:'두 수를 더하세요.',sampleInput:'1 2',sampleOutput:'3',languages:[{id:'JAVA',label:'Java 8',timeLimitMs:5000,memoryMb:384},{id:'CPP',label:'C++17',timeLimitMs:3000,memoryMb:256},{id:'PYTHON',label:'Python 3.12',timeLimitMs:8000,memoryMb:256}]});
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
    if(path==='/api/problems')data=[{version:'normal',title:'일반 문제',statement:'합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}];
    if(path==='/api/diagnostics/banks')data=[{id:'core-a-v1',categories:['implementation'],questionCount:2}];
    if(path==='/api/diagnostics'){
      if(req.method()==='POST'){
        expect(req.postDataJSON().categories).toEqual(['implementation']);
        session={id:'session',status:'ACTIVE',bankId:'core-a-v1',items:[1,2].map(n=>({id:'item'+n,position:n-1,category:'implementation',difficulty:n===1?'EASY':'MEDIUM',status:'OPEN',attempts:width===1440&&n===1?4:0,pending:0})),current:q(1)};data=session;
      }else data=session?[session]:[];
    }
    if(path==='/api/diagnostics/session')data=session;
    if(path==='/api/diagnostics/session/state'){session={...session,status:req.postDataJSON().status};data=session;}
    if(path==='/api/submissions'&&req.method()==='POST'){
      expect(req.postDataJSON().diagnosticItemId).toBe('item1');keys.push(req.headers()['idempotency-key']);
      submission={id:'saved',problemVersion:'v1',diagnosticItemId:'item1',source:req.postDataJSON().source,status:'FINISHED',verdict:width===1440?'WA':'AC',input:null};
      session={...session,items:session.items.map((i,n)=>n===0?{...i,status:width===1440?'EXHAUSTED':'PASSED',attempts:width===1440?5:1}:i),current:q(2)};
      if(keys.length===1)return route.abort();
      expect(keys[1]).toBe(keys[0]);data=submission;
    }
    if(path==='/api/submissions'&&req.method()==='GET')data=submission?[submission]:[];
    if(path==='/api/diagnostics/session/items/item2/skip'){session={...session,status:'COMPLETED',current:null,items:session.items.map((i,n)=>n===1?{...i,status:'SKIPPED'}:i)};data=session;}
    if(path==='/api/diagnostic-plans/order'){
      orderCalls++;const body=req.postDataJSON();expect(body.desired).toEqual(['round-2','plan']);
      plans=body.desired.map(id=>plans.find(p=>p.id===id));if(orderCalls===1)return route.abort();data=plans;
    }
    if(path==='/api/diagnostic-plans/options')data={reviewHash:'a'.repeat(64),observation:evaluations[0].interpretation.observations[0],corrections:evaluations[0].corrections||[],problems:width===1440?[{version:'normal',title:'일반 문제',statement:'입력을 읽어 합계를 출력합니다.'}]:[],rules:width===1440?[{id:'bfs-shortest-path-v1',label:'BFS · 무방향 그래프 최단 거리',description:'최소 이동 횟수',rules:['양방향'],verifiedReference:true}]:[]};
    if(path==='/api/diagnostic-plans'){
      if(req.method()==='POST'){
        planKeys.push(req.headers()['idempotency-key']);expect(req.postDataJSON().goal).toBe('설명에 맞게 입력 읽기');
        plans=[{id:'plan',evaluationId:'evaluation',observationIndex:0,goal:req.postDataJSON().goal,status:'READY'}];
        if(planKeys.length===1)return route.abort();
        expect(planKeys[1]).toBe(planKeys[0]);data=plans[0];
      }else data=plans;
    }
    if(path==='/api/diagnostic-plans/plan/generate'){
      generationCalls++;expect(req.postDataJSON()?.ruleVersionId).toBe(width===1440?'bfs-shortest-path-v1':undefined);
      plans=[{...plans[0],generationId:'generation',generationStatus:'PUBLISHED',generatedVersion:'normal'}];
      if(generationCalls===1)return route.abort();data=plans[0];
    }
    if(path==='/api/diagnostic-plans/plan/next-round'){
      expect(req.postDataJSON().reviewHash).toBe('a'.repeat(64));
      const next={id:'round-2',evaluationId:'evaluation',observationIndex:0,goal:plans[0].goal,status:'READY',previousPlanId:'plan',roundNumber:2};
      if(plans.length===1){plans.push(next);return route.abort();}data=plans[1];
    }
    if(path==='/api/diagnostic-plans/plan/reflect'){
      expect(req.postDataJSON()).toEqual({usedHelp:false});
      plans=[{...plans[0],status:'SELF_REPORTED_UNASSISTED_AC',usedHelp:false,reviewedSubmissionId:'training-ac'}];data=plans[0];
    }
    if(path==='/api/diagnostic-plans/plan/start'){
      expect(req.postDataJSON()).toEqual({problemVersion:'normal'});
      plans=[{...plans[0],status:'ACTIVE',sessionId:'training',problemVersion:'normal'}];
      training=[{id:'training',problemVersion:'normal',goal:plans[0].goal,status:'ACTIVE',startedAt:'2026-09-26T00:00:00Z',pending:0,submissions:0,accepted:0,runs:0}];data=plans[0];
    }
    if(path==='/api/training-sessions')data=training;
    if(path.endsWith('/corrections')){
      correctionKeys.push(req.headers()['idempotency-key']);
      expect(req.postDataJSON()).toEqual({observationIndex:0,note:'문법보다 입력 설명을 잘못 읽었습니다.'});
      evaluations=[{...evaluations[0],corrections:[{id:'correction',observationIndex:0,note:req.postDataJSON().note}]}];
      if(correctionKeys.length===1)return route.abort();
      expect(correctionKeys[1]).toBe(correctionKeys[0]);data=evaluations[0];
    }
    if(path.endsWith('/evaluations')){
      if(req.method()==='POST')evaluations=[{id:'evaluation',facts:{complete:true,items:session.items.map(i=>({...i,itemId:i.id}))},status:'COMPLETED',interpretation:{summary:'완료한 문항의 코드만 확인했습니다.',uncertainty:'다른 분야는 미평가입니다.',observations:[{submissionId:'saved',quote:'class Main',interpretation:'코드 근거 예시',confidence:'UNCERTAIN',nextAction:width===1440?'PRACTICE':'ASSESS',recommendation:width===1440?'입력 처리 연습':'입력 처리 추가 진단'}]}}];
      data=req.method()==='POST'?evaluations[0]:evaluations;
    }
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await page.getByRole('button',{name:'선택한 2문항 시작'}).click();
  await expect(page.getByRole('heading',{name:'진단 문항 1'})).toBeVisible();
  await expect(page.locator('.workspace-heading')).toBeHidden();
  expect((await page.locator('.app-navigation').boundingBox()).width).toBeLessThanOrEqual(52);
  expect((await page.locator('#diagnostic-source').boundingBox()).y).toBeLessThan(width>850?180:330);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  if(width<=850){
    await page.getByRole('button',{name:'문제 보기',exact:true}).click();
    await expect(page.locator('#diagnostic-problem-content')).toBeVisible();
    await page.getByRole('button',{name:'문제 접기',exact:true}).click();
    await expect(page.locator('#diagnostic-problem-content')).toBeHidden();
  }
  await page.screenshot({path:`/tmp/gamja-diagnostic-compact-${width}.png`,fullPage:true});

  const height=page.getByRole('separator',{name:'진단 편집기 높이 조절',exact:true});
  await height.focus();await height.press('Home');
  expect((await page.locator('#diagnostic-source').boundingBox()).height).toBeCloseTo(158,0);
  await page.getByRole('region',{name:'선택 진단',exact:true}).getByText('편집기 크기',{exact:true}).click();
  await page.getByRole('button',{name:'크기 초기화',exact:true}).click();
  await page.getByRole('region',{name:'선택 진단',exact:true}).getByText('편집기 크기',{exact:true}).click();
  if(width>850){const split=page.getByRole('separator',{name:'진단 문제와 편집기 비율',exact:true});await split.focus();await split.press('ArrowLeft');await expect(split).toHaveAttribute('aria-valuenow','48');}
  await page.getByLabel('진단 언어',{exact:true}).selectOption('PYTHON');
  const pythonEditor=page.getByLabel('진단 Python 3.12 코드',{exact:true});
  await pythonEditor.fill('defaul');await pythonEditor.press('Control+End');await pythonEditor.press('Control+Space');
  await expect(page.getByRole('option',{name:'defaultdict',exact:false})).toBeVisible();
  await pythonEditor.press('Escape');
  await page.getByRole('button',{name:'사이드바 펼치기'}).click();
  await expect(page.getByRole('button',{name:'사이드바 접기'})).toHaveAttribute('aria-expanded','true');
  await page.screenshot({path:`/tmp/gamja-sidebar-diagnostic-${width}.png`});
  await page.getByRole('button',{name:'사이드바 접기'}).click();
  await expect(pythonEditor).toContainText('defaul');
  await page.getByLabel('진단 Python 3.12 코드',{exact:true}).fill('print(3)');
  await page.getByLabel('진단 언어',{exact:true}).selectOption('CPP');
  await expect(page.getByLabel('진단 C++17 코드',{exact:true})).toContainText('#include');
  const cppEditor=page.getByLabel('진단 C++17 코드',{exact:true});
  await cppEditor.fill('priority_q');await cppEditor.press('Control+End');await cppEditor.press('Control+Space');
  await expect(page.getByRole('option',{name:'priority_queue',exact:false})).toBeVisible();
  await cppEditor.press('Escape');

  await page.getByLabel('진단 언어',{exact:true}).selectOption('PYTHON');
  await expect(page.getByLabel('진단 Python 3.12 코드',{exact:true})).toHaveText('print(3)');
  await page.getByLabel('진단 언어',{exact:true}).selectOption('JAVA');
  await page.locator('#diagnostic-source .cm-content').fill('class Main { /* saved draft */ }');
  await page.getByRole('button',{name:'일시정지',exact:true}).click();
  await expect(page.getByRole('button',{name:/정식 제출/})).toBeDisabled();
  await page.getByRole('button',{name:'일반 연습으로'}).click();
  await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await expect(page.locator('#diagnostic-source')).toContainText('saved draft');
  await page.getByRole('button',{name:'진단 이어서 풀기'}).click();
  await page.getByRole('button',{name:/정식 제출/}).click();
  await page.getByRole('button',{name:'요청 다시 확인'}).click();
  await expect(page.getByRole('heading',{name:'진단 문항 2'})).toBeVisible();
  await expect(page.getByText(width===1440?'이전 문항: 5회 소진':'이전 문항: 통과')).toBeVisible();
  await page.screenshot({path:`/tmp/gamja-diagnostic-${width}.png`,fullPage:true});
  await page.getByRole('button',{name:'모르겠어요 · 건너뛰기'}).click();
  await expect(page.getByText(/진단을 마쳤어요/)).toBeVisible();
  await expect(page.locator('.workspace-heading')).toBeVisible();
  await page.getByRole('button',{name:'종합 평가 요청'}).click();
  await expect(page.getByText('완료한 문항의 코드만 확인했습니다.')).toBeVisible();
  await expect(page.getByText(width===1440?'연습 제안: 입력 처리 연습':'추가 진단 제안: 입력 처리 추가 진단')).toBeVisible();
  await page.getByText('이 해석에 의견 남기기',{exact:true}).click();
  await page.getByLabel('관찰 1 정정 설명').fill('문법보다 입력 설명을 잘못 읽었습니다.');
  await page.getByRole('button',{name:'정정 의견 저장'}).click();
  await page.getByRole('button',{name:'같은 정정 다시 확인'}).click();
  await expect(page.getByText('내 정정 의견: 문법보다 입력 설명을 잘못 읽었습니다.')).toBeVisible();
  await expect(page.getByText('완료한 문항의 코드만 확인했습니다.')).toBeVisible();
  await page.getByRole('button',{name:'이 제안으로 학습 계획 준비'}).click();
  if(width===1440){
    await expect(page.getByText('이 관찰에는 정정 의견이 있습니다.',{exact:false})).toBeVisible();
    await page.getByLabel('내가 확인한 연습 목표').fill('설명에 맞게 입력 읽기');
    await page.getByRole('button',{name:'이 목표를 내 학습 계획에 저장'}).click();
    await page.getByRole('button',{name:'같은 목표 저장 다시 확인'}).click();
    await page.getByLabel('직접 고를 연습 문제').selectOption('normal');
    await page.screenshot({path:`/tmp/gamja-diagnostic-plan-${width}.png`,fullPage:true});
    const generateName=width===1440?'선택한 규칙으로 문제 생성':'이 목표로 맞춤 문제 생성 요청';
    if(width===1440){await expect(page.getByRole('button',{name:generateName})).toBeDisabled();await page.getByLabel('검증된 규칙으로 바로 만들기').selectOption('bfs-shortest-path-v1');
      await expect(page.getByText('검증된 정답 코드를 다시 사용합니다',{exact:false})).toBeVisible();}
    else await expect(page.getByLabel('검증된 규칙으로 바로 만들기')).toHaveCount(0);
    await page.getByRole('button',{name:generateName}).click();
    await page.getByRole('button',{name:generateName}).click();
    await expect(page.getByRole('button',{name:'생성·검증 화면으로'})).toBeVisible();
    await page.getByRole('button',{name:'생성된 문제로 훈련 시작'}).click();
    expect(generationCalls).toBe(2);
    await expect(page.getByRole('button',{name:'문제 풀기',exact:true})).toHaveAttribute('aria-pressed','true');
    plans=[{...plans[0],status:'TRAINING_ENDED'}];training=[];
    await page.getByRole('button',{name:'선택 진단',exact:true}).click();
    await page.getByText('지난 진단',{exact:true}).click();
    await page.getByRole('button',{name:'2문항 · 완료'}).click();
    await page.getByRole('button',{name:'이 제안으로 학습 계획 준비'}).click();
    await page.getByRole('button',{name:'도움 없이 풀었어요'}).click();
    await expect(page.getByText(/본인이 보고한 도움 없는 AC로 기록/)).toBeVisible();
    await page.getByRole('button',{name:'최신 의견 확인 · 다음 회차 준비'}).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await page.getByRole('button',{name:'최신 의견 확인 · 다음 회차 준비'}).click();
    await expect(page.getByRole('heading',{name:'2회차 · 설명에 맞게 입력 읽기'})).toBeVisible();
    await expect(page.getByRole('button',{name:'최신 의견 확인 · 다음 회차 준비'})).toHaveCount(0);
    await expect(page.getByText('본인이 보고한 도움 없는 AC로 기록했습니다.',{exact:false})).toBeVisible();
    await page.getByRole('button',{name:'학습 순서 불러오기'}).click();
    await page.getByRole('button',{name:'1번째 목표 아래로'}).click();
    await page.getByRole('button',{name:'같은 순서 저장 다시 확인'}).click();
    await expect(page.getByRole('region',{name:'학습 순서'})).toContainText('순서상 다음 목표');
    await expect.poll(()=>orderCalls).toBe(2);
    await expect(page.getByRole('button',{name:'같은 순서 저장 다시 확인'})).toHaveCount(0);
    await expect(page.getByRole('region',{name:'학습 순서'}).locator('li').first()).toContainText('2회차');
    await page.screenshot({path:'/tmp/gamja-diagnostic-curriculum.png',fullPage:true});
    await page.setViewportSize({width:390,height:900});
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.screenshot({path:'/tmp/gamja-diagnostic-curriculum-mobile.png',fullPage:true});
    await page.getByRole('button',{name:'해당 관찰·계획으로 이동'}).click();
    await expect(page.locator('#diagnostic-observation-evaluation-0')).toBeFocused();
  }else{
    await expect(page.getByText(/부족함을 확정한 연습 처방이 아니라 추가 확인/)).toBeVisible();
    await expect(page.getByRole('button',{name:'이 목표를 내 학습 계획에 저장'})).toHaveCount(0);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('unreviewed bank leaves ordinary practice available',async({page})=>{
  await page.route('**/api/**',async route=>{
    let data=[];if(new URL(route.request().url()).pathname==='/api/me')data={id:'learner',nickname:'연습'};
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await expect(page.getByText(/검토가 끝난 진단 문항을 준비/)).toBeVisible();
  await page.getByRole('button',{name:'일반 연습으로'}).click();
  await expect(page.getByRole('button',{name:'문제 풀기',exact:true})).toHaveAttribute('aria-pressed','true');
});
for(const width of [390,1440])test('reassessment selection and known-question retry at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  const original={id:'a',bankId:'a',status:'COMPLETED',current:null,items:[{id:'a1',category:'implementation',status:'SKIPPED',attempts:0,pending:0}]};
  let second=null,starts=[],reports=0;
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
    if(path==='/api/diagnostics')data=second?[second,original]:[original];
    if(path==='/api/diagnostic-plans/trained-scope')data=['implementation'];
    if(path==='/api/diagnostics/a/reassessments'){
      if(req.method()==='GET')data=[{id:'b',categories:['implementation'],questionCount:2}];
      else{
        expect(req.postDataJSON()).toEqual({bankId:'b',categories:['implementation']});starts.push(req.headers()['idempotency-key']);
        second={id:'b-session',bankId:'b',sourceSessionId:'a',status:'ACTIVE',items:[1,2].map(n=>({id:'b'+n,category:'implementation',difficulty:n===1?'EASY':'MEDIUM',position:n-1,status:'OPEN',attempts:0,pending:0})),current:{itemId:'b1',problemVersion:'b1',title:'재평가 첫 문항',statement:'새 문제',sampleInput:'1',sampleOutput:'1'}};
        if(starts.length===1)return route.abort();expect(starts[1]).toBe(starts[0]);data=second;
      }
    }
    if(path==='/api/diagnostics/b-session')data=second;
    if(path==='/api/diagnostics/b-session/items/b2/skip'){
      second={...second,status:'COMPLETED',current:null,items:second.items.map((i,n)=>n===1?{...i,status:'SKIPPED'}:i)};data=second;
    }
    if(path==='/api/diagnostics/b-session/items/b2/exposure'){
      second={...second,items:second.items.map((i,n)=>n===1?{...i,externallySeen:true}:i)};data=second;
    }
    if(path==='/api/diagnostics/b-session/items/b1/exposure'){
      reports++;second={...second,items:second.items.map((i,n)=>n===0?{...i,status:'SKIPPED',externallySeen:true}:i),current:{...second.current,itemId:'b2',problemVersion:'b2',title:'재평가 다음 문항'}};
      if(reports===1)return route.abort();data=second;
    }
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await page.getByText('지난 진단',{exact:true}).click();await page.getByRole('button',{name:'1문항 · 완료'}).click();
  await page.getByRole('button',{name:'재평가 가능한 분야 확인'}).click();
  await expect(page.getByRole('button',{name:'선택한 0문항 재평가 시작'})).toBeDisabled();
  await page.getByRole('button',{name:'훈련에 연결된 분야 선택'}).click();await expect(page.getByRole('checkbox')).toBeChecked();await page.getByRole('button',{name:'선택한 2문항 재평가 시작'}).click();
  await expect(page.getByRole('alert').filter({hasText:'Failed to fetch'})).toBeVisible();
  await page.reload();await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await page.getByRole('button',{name:'요청 다시 확인'}).click();
  await expect(page.getByRole('heading',{name:'재평가 첫 문항'})).toBeVisible();
  await page.getByRole('button',{name:'이 문제나 풀이를 본 적 있어요 · 기록 후 건너뛰기'}).click();
  await page.getByRole('button',{name:'요청 다시 확인'}).click();
  await expect(page.getByRole('heading',{name:'재평가 다음 문항'})).toBeVisible();
  await page.getByText('문항별 진행과 제출 기록',{exact:true}).click();
  await expect(page.getByText(/본 적 있음 · 평가 근거에서 제외/)).toBeVisible();
  expect(reports).toBe(2);expect(starts).toHaveLength(2);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByRole('button',{name:'모르겠어요 · 건너뛰기'}).click();
  await expect(page.getByText('진단을 마쳤어요.',{exact:false})).toBeVisible();
  await page.getByRole('button',{name:'2번 문항 · 이전에 본 문제로 정정'}).click();
  await expect(page.getByRole('button',{name:'2번 문항 · 이전에 본 문제로 정정'})).toHaveCount(0);
  await expect(page.getByText(/본 적 있음 · 평가 근거에서 제외/)).toHaveCount(2);
  await expect(page.getByRole('heading',{name:'다른 문제로 재평가'})).toHaveCount(1);
  await page.screenshot({path:`/tmp/gamja-reassessment-late-${width}.png`,fullPage:true});
});
