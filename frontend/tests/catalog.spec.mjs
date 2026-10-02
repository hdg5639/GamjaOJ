import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,768,1440])test(`home catalog filters, sharing and editor continuity at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:950});
  let problems=[
    {version:'seed',title:'두 수의 합',category:'구현',tags:['입출력'],difficulty:'EASY',thinking:{layer:1,name:'그대로',source:'CURATED_ESTIMATE',insight:1,implementation:1,edgeCases:1,rationale:'명시된 계산을 수행해요.'},shared:true},
    {version:'mine',title:'내 비공개 그래프 연습',category:'그래프',tags:['BFS'],difficulty:'MEDIUM',thinking:{layer:3,name:'골라쓰기',source:'AUTHOR_ESTIMATE',insight:2,implementation:2,edgeCases:3,rationale:'도구를 골라요.'},shared:false,mine:true,generated:true},
    {version:'other',title:'다른 회원의 최단 경로',category:'그래프',tags:['BFS','방문 처리'],difficulty:'MEDIUM',thinking:{layer:5,name:'뒤집어보기',source:'CURATED_ESTIMATE',insight:4,implementation:2,edgeCases:3,rationale:'관점을 바꿔요.'},shared:true,mine:false,generated:true},
    {version:'held',title:'보류된 문제',category:'그래프',tags:[],shared:true,problemHeld:true},
  ].map(p=>({...p,statement:'정점 사이의 경로를 구하세요.',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}));
  let saves=0;
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'감자'};
    if(path==='/api/problems')data=problems;
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
    if(path==='/api/problems/mine/catalog-settings'){
      expect(req.method()).toBe('PUT');const body=req.postDataJSON();saves++;
      expect(body).toEqual({shared:true,category:'그래프',tags:['BFS','상태 관리'],difficulty:'MEDIUM',thinking:{layer:5,insight:2,implementation:2,edgeCases:3,rationale:'질문의 방향을 바꿔 접근해요.'}});
      problems=problems.map(p=>p.version==='mine'?{...p,...body}:p);data=problems.find(p=>p.version==='mine');
      if(saves===1)return route.abort(); // committed settings, lost response; explicit same-value retry
    }
    await route.fulfill({json:data});
  });
  await page.goto(base);
  await expect(page.getByRole('heading',{name:'오늘 풀 문제를 골라보세요.'})).toBeVisible();
  const list=page.locator('.catalog-list');
  await expect(list.locator('li')).toHaveCount(2);
  await page.screenshot({path:`/tmp/gamja-shared-entry-${width}.png`,fullPage:true});
  await expect(list).not.toContainText('보류된 문제');
  await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
  await page.goBack();
  await expect(page.getByRole('heading',{name:'오늘 풀 문제를 골라보세요.'})).toBeVisible();
  await page.getByRole('button',{name:'다른 사람의 문제',exact:true}).click();
  await expect(list.locator('li')).toHaveCount(1);await expect(list).toContainText('다른 회원의 최단 경로');
  if(width<=800){
    await expect(page.getByLabel('난이도',{exact:true})).toBeHidden();
    const toggle=page.getByRole('button',{name:/^필터/});
    await toggle.focus();await toggle.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded','true');
  }
  await page.getByLabel('난이도',{exact:true}).selectOption('1');
  await expect(page.getByText('일치하는 문제가 없어요.',{exact:false})).toBeVisible();
  await page.getByRole('button',{name:'검색 초기화',exact:true}).click();
  await page.getByRole('button',{name:'내가 만든 문제',exact:true}).click();
  await expect(list).toContainText('비공개');
  await page.getByRole('button',{name:'공개·분류 설정',exact:true}).click();
  const form=page.getByRole('form',{name:'공개·분류 설정'});
  await form.getByLabel('다른 회원에게 공개').check();await form.getByLabel('분류 태그').fill('BFS, 상태 관리');
  await form.getByLabel('예상 난이도').selectOption('5');await form.getByLabel('배정 근거').fill('질문의 방향을 바꿔 접근해요.');
  await form.getByRole('button',{name:'설정 저장'}).click();await expect(form.getByRole('alert')).toBeVisible();
  await form.getByRole('button',{name:'설정 저장'}).click();await expect(form).toBeHidden();
  expect(saves).toBe(2);
  await page.getByRole('button',{name:'전체 공개 문제',exact:true}).click();
  await page.getByLabel('분야',{exact:true}).selectOption('그래프');await page.getByLabel('태그',{exact:true}).selectOption('상태 관리');
  await expect(list.locator('li')).toHaveCount(1);await expect(list).toContainText('뒤집어보기');
  await page.getByRole('button',{name:/내 비공개 그래프 연습 · mine .*풀기/}).click();
  if(width<=800)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
  const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('// browsing preserves my draft');
  await page.getByRole('button',{name:'문제 탐색',exact:true}).click();
  await page.screenshot({path:`/tmp/gamja-shared-home-${width}.png`,fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByRole('button',{name:/내 비공개 그래프 연습 · mine 이어서 풀기/}).click();
  if(width<=800)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
  await expect(editor).toContainText('// browsing preserves my draft');
  await page.reload();await expect(editor).toContainText('// browsing preserves my draft');
});

for(const width of [390,1440])test(`personal solve filters use catalog facts and refresh after judging at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:950});
  let finished=false, polls=0;
  const records=[
    {version:'old',title:'오래전에 해결한 문제',solveStatus:'SOLVED'},
    {version:'new',title:'아직 제출하지 않은 문제',solveStatus:'UNATTEMPTED'},
    {version:'pending',title:'채점 중인 문제',solveStatus:'ATTEMPTED',pendingSubmissions:1},
    {version:'unknown',title:'상태가 없는 이전 응답'},
  ].map(p=>({...p,statement:'합',sampleInput:'1 2',sampleOutput:'3',shared:true,submissionsEnabled:true,category:'구현',difficulty:'EASY',tags:['입출력']}));
  const submission=()=>({id:'pending-submission',problemVersion:'pending',status:finished?'FINISHED':'QUEUED',verdict:finished?'AC':null});
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let data=[];
    if(path==='/api/me')data={id:'progress-user',username:'progress-user',nickname:'풀이'};
    if(path==='/api/problems')data=records.map(p=>p.version==='pending'&&finished?{...p,solveStatus:'SOLVED',pendingSubmissions:0}:p);
    if(path==='/api/submissions'){polls++;data=[submission()];} // The old AC is intentionally absent from recent history.
    await route.fulfill({json:data});
  });
  await page.goto(base);
  const list=page.locator('.catalog-list');
  await expect(list).toContainText('채점 중 1건');
  if(!await page.getByLabel('내 풀이 상태').isVisible())await page.getByRole('button',{name:/^필터/}).click();
  await page.getByLabel('내 풀이 상태').selectOption('SOLVED');
  await expect(list.locator('li')).toHaveCount(1);await expect(list).toContainText('오래전에 해결한 문제');
  await page.getByLabel('내 풀이 상태').selectOption('UNSOLVED');
  await expect(list.locator('li')).toHaveCount(2);await expect(list).not.toContainText('상태가 없는 이전 응답');
  await page.getByLabel('내 풀이 상태').selectOption('UNATTEMPTED');
  await expect(list.locator('li')).toHaveCount(1);await expect(list).toContainText('아직 제출하지 않은 문제');
  await page.getByLabel('내 풀이 상태').selectOption('ATTEMPTED');
  await expect(list).toContainText('채점 중인 문제');
  finished=true;
  await expect(list).toBeHidden({timeout:10000}); // completion refresh removes it from the active filter, without a page reload
  await page.getByRole('button',{name:'검색 초기화',exact:true}).click();
  await expect(page.getByLabel('내 풀이 상태')).toHaveValue('');
  if(!await page.getByLabel('내 풀이 상태').isVisible())await page.getByRole('button',{name:/^필터/}).click();
  await page.getByLabel('내 풀이 상태').selectOption('SOLVED');
  await expect(list.locator('li')).toHaveCount(2);await expect(list).not.toContainText('채점 중 1건');
  expect(polls).toBeGreaterThan(1);
  await list.locator('li').first().scrollIntoViewIfNeeded();
  await page.screenshot({path:`/tmp/gamja-progress-${width}.png`,fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByLabel('내 풀이 상태').selectOption('');
  await expect(list).toContainText('풀이 기록 확인 전');
});

test('own problem deletion asks first and refreshes the catalog',async({page})=>{
  let problems=[{version:'seed',title:'두 수의 합',category:'구현',tags:[],shared:true},
    {version:'mine',title:'내가 만든 연습',category:'그래프',tags:[],shared:false,mine:true,generated:true}]
    .map(p=>({...p,statement:'합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:true}));
  const deletes=[];
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'감자'};
    if(path==='/api/problems')data=problems;
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
    if(path==='/api/problems/mine'&&req.method()==='DELETE'){deletes.push(path);problems=problems.filter(p=>p.version!=='mine');data={outcome:'DELETED'};}
    await route.fulfill({json:data});
  });
  await page.goto(base);
  await page.getByRole('button',{name:'내가 만든 문제',exact:true}).click();
  await page.getByRole('button',{name:'삭제',exact:true}).click();
  const confirm=page.getByRole('group',{name:'내가 만든 연습 삭제 확인'});
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button',{name:'취소'}).click();
  expect(deletes).toHaveLength(0);
  await page.getByRole('button',{name:'삭제',exact:true}).click();
  await page.getByRole('group',{name:'내가 만든 연습 삭제 확인'}).getByRole('button',{name:'삭제하기'}).click();
  await expect(page.getByText('내가 만든 연습을(를) 삭제했어요.')).toBeVisible();
  expect(deletes).toEqual(['/api/problems/mine']);
  await expect(page.getByRole('heading',{name:'내가 만든 연습'})).toHaveCount(0);
});

test('own held problems appear only under my problems where they can be deleted',async({page})=>{
  let problems=[{version:'seed',title:'두 수의 합',category:'구현',tags:[],shared:true},
    {version:'held-mine',title:'보류된 내 문제',category:'그래프',tags:[],shared:false,mine:true,generated:true,problemHeld:true,reviewReason:'예제 출력 오류'},
    {version:'held-other',title:'보류된 남의 문제',category:'그래프',tags:[],shared:true,mine:false,generated:true,problemHeld:true}]
    .map(p=>({...p,statement:'합',sampleInput:'1 2',sampleOutput:'3',submissionsEnabled:!p.problemHeld}));
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'감자'};
    if(path==='/api/problems')data=problems;
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
    if(path==='/api/problems/held-mine'&&req.method()==='DELETE'){problems=problems.filter(p=>p.version!=='held-mine');data={outcome:'DELETED'};}
    await route.fulfill({json:data});
  });
  await page.goto(base);
  await expect(page.getByRole('heading',{name:'두 수의 합'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'보류된 내 문제'})).toHaveCount(0);
  await page.getByRole('button',{name:'내가 만든 문제',exact:true}).click();
  await expect(page.getByText('검토 보류 중 · 예제 출력 오류',{exact:false})).toBeVisible();
  await expect(page.getByRole('heading',{name:'보류된 남의 문제'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'공개·분류 설정'})).toHaveCount(0);
  await page.getByRole('button',{name:'삭제',exact:true}).click();
  await page.getByRole('group',{name:'보류된 내 문제 삭제 확인'}).getByRole('button',{name:'삭제하기'}).click();
  await expect(page.getByText('보류된 내 문제을(를) 삭제했어요.')).toBeVisible();
});
