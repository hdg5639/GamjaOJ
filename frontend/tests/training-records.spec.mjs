import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
for(const width of [390,820,1710])test(`training records search, replay and navigation at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:1000});
  await page.addInitScript(()=>{
    localStorage.setItem('gamjaoj-theme','dark');
    localStorage.setItem('gamjaoj-appearance-v1',JSON.stringify({dark:{canvas:'#101827',surface:'#18243a',subtle:'#22334d',ink:'#e6eefb',muted:'#a7b8d1',line:'#354760',green:'#568bc9'}}));
  });
  const problems=['one','two','held'].map((version,i)=>({version,title:['배열의 경계 살펴보기','너비 우선 탐색으로 길 찾기','검토 중인 문제'][i],statement:'두 수를 더하세요.',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true,problemHeld:version==='held'}));
  let sessions=Array.from({length:15},(_,i)=>({id:`past-${i}`,problemVersion:i%2?'two':'one',goal:`경계 조건 확인 ${i}`,note:'범위를 먼저 확인했어요.',status:'ENDED',startedAt:'2026-10-01T10:00:00Z',endedAt:'2026-10-01T10:10:00Z',submissions:2,accepted:1,pending:0}));
  let startKeys=[],startBodies=[],endBodies=[],aiPosts=0;
  const entry={id:'entry',kind:'SUBMISSION',status:'FINISHED',verdict:'AC',createdAt:'2026-10-01T10:05:00Z',input:null,source:'class Main { /* 당시 저장된 코드 */ }'};
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습',trainingGoal:'입력 범위 점검'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
    if(path==='/api/problems')data=problems;
    if(path==='/api/training-sessions'){
      if(req.method()==='POST'){
        startKeys.push(req.headers()['idempotency-key']);startBodies.push(req.postDataJSON());
        if(startKeys.length===1){sessions=[{id:'active',...startBodies[0],status:'ACTIVE',startedAt:'2026-10-03T10:00:00Z',submissions:0,accepted:0,pending:0},...sessions];return route.abort();}
        data=sessions[0];
      }else data=sessions;
    }
    if(path.startsWith('/api/training-sessions/')&&!path.endsWith('/end'))data={session:sessions.find(item=>item.id===path.split('/').at(-1)),entries:[entry]};
    if(path==='/api/training-sessions/active/end'){
      endBodies.push(req.postDataJSON());sessions=sessions.map(item=>item.id==='active'?{...item,status:'ENDED',note:endBodies[0].note,endedAt:'2026-10-03T10:20:00Z'}:item);data=sessions[0];
      if(endBodies.length===1)return route.abort();
    }
    if(path==='/api/submissions/entry')data=entry;
    if(path==='/api/ai/tasks'&&req.method()==='POST')aiPosts++;
    await route.fulfill({json:data});
  });
  await page.goto(base+'/#training');
  const panel=page.getByRole('region',{name:'훈련 세션',exact:true});
  await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();
  await expect(panel.getByRole('button',{name:'직접 훈련 시작',exact:true})).toBeVisible();
  await expect(panel.locator('.training-record-row')).toHaveCount(10);
  await panel.getByRole('navigation',{name:'훈련 기록 페이지'}).getByRole('button',{name:'다음'}).click();
  await expect(panel.locator('.training-record-row')).toHaveCount(5);
  await panel.getByLabel('훈련 기록 검색').fill('길 찾기');
  await expect(panel.locator('.training-record-row')).toHaveCount(7);
  await panel.getByLabel('훈련 상태',{exact:true}).selectOption('ACTIVE');
  await expect(panel.getByText('조건에 맞는 기록이 없어요. 검색어나 상태를 바꿔 보세요.')).toBeVisible();
  await panel.getByRole('button',{name:'초기화',exact:true}).click();
  await expect(panel.locator('.training-record-row')).toHaveCount(10);
  await panel.locator('.training-record-row').first().click();
  await expect(page.locator('#training-detail-heading')).toBeFocused();
  await expect(panel.getByLabel('저장된 마무리 메모')).toHaveText('범위를 먼저 확인했어요.');
  await panel.getByRole('button',{name:/정식 제출 · AC · 정답/}).click();
  await expect(panel.getByLabel('훈련에 저장된 코드')).toContainText('당시 저장된 코드');
  await page.getByRole('dialog',{name:'훈련 상세 기록'}).getByRole('button',{name:'닫기',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'훈련 상세 기록'})).toBeHidden();
  await panel.getByRole('button',{name:'직접 훈련 시작',exact:true}).click();
  await panel.getByLabel('훈련할 문제').selectOption('two');
  await panel.getByLabel('이번 훈련 목표').fill('큐에 넣는 시점 확인');
  await page.locator('.training-hub-view').evaluate(el=>el.scrollTo(0,0));
  await page.screenshot({path:`/tmp/gamja-training-start-${width}.png`,fullPage:true});
  await panel.getByRole('button',{name:'훈련 시작',exact:true}).click();
  await expect(panel.getByRole('button',{name:'같은 훈련 요청 다시 확인'})).toBeEnabled();
  await page.reload();
  await panel.getByRole('button',{name:'같은 훈련 요청 다시 확인'}).click();
  await expect.poll(()=>startKeys.length).toBe(2);expect(startKeys[1]).toBe(startKeys[0]);expect(startBodies).toEqual([{problemVersion:'two',goal:'큐에 넣는 시점 확인'},{problemVersion:'two',goal:'큐에 넣는 시점 확인'}]);
  await expect(panel.getByRole('button',{name:'훈련 이어 풀기'})).toBeEnabled();
  await panel.getByRole('button',{name:'훈련 이어 풀기'}).click();
  await expect(page.getByLabel('풀이할 문제')).toHaveValue('two');
  await page.getByRole('button',{name:'훈련 기록',exact:true}).click();
  await panel.getByRole('button',{name:'훈련 마무리',exact:true}).click();
  await panel.getByLabel('마무리 메모').fill('방문 처리를 큐에 넣을 때 했어요.');
  await page.locator('.training-hub-view').evaluate(el=>el.scrollTo(0,0));
  await page.screenshot({path:`/tmp/gamja-training-active-${width}.png`,fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  const finishDialog=page.getByRole('dialog',{name:'훈련 마무리'});
  await expect(finishDialog).toHaveCSS('backdrop-filter',/blur/);
  const tint=await finishDialog.evaluate(n=>{const c=document.createElement('canvas').getContext('2d');c.fillStyle=getComputedStyle(n).backgroundColor;c.fillRect(0,0,1,1);return [...c.getImageData(0,0,1,1).data];});
  expect(tint.slice(0,3)).toEqual([24,36,58]);expect(tint[3]).toBeGreaterThan(150);expect(tint[3]).toBeLessThan(240);
  await panel.getByRole('button',{name:'훈련 마치기'}).click();
  await expect(panel.getByRole('button',{name:'같은 훈련 요청 다시 확인'})).toBeEnabled();
  await page.reload();
  await panel.getByRole('button',{name:'같은 훈련 요청 다시 확인'}).click();
  await expect.poll(()=>endBodies.length).toBe(2);
  expect(endBodies).toEqual([{note:'방문 처리를 큐에 넣을 때 했어요.'},{note:'방문 처리를 큐에 넣을 때 했어요.'}]);
  await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();
  await panel.locator('.training-record-row').first().click();
  await expect(panel.getByLabel('저장된 마무리 메모')).toHaveText('방문 처리를 큐에 넣을 때 했어요.');
  expect(aiPosts).toBe(0);
  await page.getByRole('dialog',{name:'훈련 상세 기록'}).getByRole('button',{name:'닫기',exact:true}).click();
  await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'학습 계획',exact:true}).click();
  await page.getByRole('button',{name:'진단·수동 계획 만들기'}).click();
  await expect(page.getByRole('heading',{name:'나에게 맞는 시작점 찾기'})).toBeVisible();
});
for(const held of [false,true])test(`training empty and held states with followup paging ${held}`,async({page})=>{
  await page.setViewportSize({width:390,height:1000});
  const version='a-very-long-private-problem-version-for-preserved-record';
  const session={id:'held',problemVersion:version,goal:'범위 확인',status:'ACTIVE',problemHeld:true,submissions:1,accepted:0,pending:0,startedAt:'2026-10-03T10:00:00Z'};
  let fail=true;
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner'};
    if(path==='/api/problems')data=[{version,title:'다시 검토 중인 문제',problemHeld:held,statement:'입력',submissionsEnabled:true}];
    if(path==='/api/training-sessions')data=held?[session]:[];
    if(path==='/api/practice-followups'){
      if(fail){fail=false;return route.fulfill({status:503,json:{message:'목록을 불러오지 못했어요.'}});}
      data=held?Array.from({length:6},(_,i)=>({id:`goal-${i}`,goal:`경계값을 따로 확인하는 연습 목표 ${i}`,status:'HELD',round:1,candidates:[]})):[];
    }
    await route.fulfill({json:data});
  });
  await page.goto(base+'/#training');
  const panel=page.getByRole('region',{name:'훈련 세션',exact:true}),followup=page.getByRole('region',{name:'다음 훈련',exact:true});
  await page.getByText('제출 피드백으로 만든 연습 목표',{exact:true}).click();
  await expect(followup.getByRole('alert')).toBeVisible();
  await followup.getByRole('button',{name:'목록 다시 불러오기'}).click();
  if(held){
    await expect(panel.getByRole('button',{name:'훈련 이어 풀기'})).toBeDisabled();
    await panel.getByRole('button',{name:'훈련 마무리',exact:true}).click();
    await expect(panel.getByRole('button',{name:'훈련 마치기'})).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(followup.locator('.followup-record')).toHaveCount(5);
    await followup.getByRole('navigation',{name:'다음 훈련 페이지'}).getByRole('button',{name:'다음'}).click();
    await expect(followup.locator('.followup-record')).toHaveCount(1);
    await expect(followup).toContainText('연습 목표 5');
    await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();
    await expect(panel.locator('.training-record-main small')).not.toContainText(version);
  }else{
    await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'내 훈련 기록',exact:true}).click();
    await expect(panel).toContainText('아직 훈련 기록이 없어요.');
    await page.getByRole('navigation',{name:'훈련 화면'}).getByRole('button',{name:'학습 계획',exact:true}).click();
    await expect(followup).toContainText('제출 코드의 피드백에서');
    await expect(page.getByText('AI 분석 예산·사용량',{exact:true})).toHaveCount(0);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
