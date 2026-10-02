import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,768,1710])test(`diagnostic report history and evidence at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:1000});
  await page.addInitScript(()=>{
    localStorage.setItem('gamjaoj-theme','dark');
    localStorage.setItem('gamjaoj-appearance-v1',JSON.stringify({dark:{canvas:'#101827',surface:'#18243a',subtle:'#22334d',ink:'#e6eefb',muted:'#a7b8d1',line:'#354760',green:'#568bc9'}}));
  });
  const checkPanel=async()=>{
    const geometry=await page.locator('.diagnostic-panel').evaluate(el=>{
      const style=getComputedStyle(el),panel=el.getBoundingClientRect(),heading=el.querySelector('.diagnostic-heading').getBoundingClientRect();
      return {padding:parseFloat(style.paddingLeft),inset:heading.left-panel.left,background:style.backgroundColor};
    });
    expect(geometry.padding).toBeGreaterThanOrEqual(16);
    expect(geometry.inset).toBeGreaterThanOrEqual(16);
    expect(geometry.background).toBe('rgb(24, 36, 58)');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  };
  const items=['PASSED','EXHAUSTED','PASSED','SKIPPED'].map((status,i)=>({id:`i${i}`,itemId:`i${i}`,position:i,category:i<2?'implementation':'graph',difficulty:i%2?'MEDIUM':'EASY',status,attempts:status==='EXHAUSTED'?5:status==='SKIPPED'?0:1,pending:0}));
  const observation={submissionId:'submission-123',pattern:'경계 조건을 분리해서 확인합니다',tone:'STRENGTH',confidence:'SUPPORTED',quote:'if (distance[next] > cost) {\n    distance[next] = cost;\n}',interpretation:'최단 거리가 갱신되는 경우에만 다음 정점을 방문합니다.',risk:'같은 정점을 불필요하게 반복 처리하는 일을 줄일 수 있어요.',nextAction:'PRACTICE',recommendation:'도달할 수 없는 정점이 있는 그래프에서도 같은 조건을 확인해 보세요.'};
  const session={id:'report-session',status:'COMPLETED',items,current:null,bankId:'algo-mix-a-v1'};
  const reports=[{id:'latest',createdAt:'2026-09-30T10:00:00Z',status:'COMPLETED',facts:{complete:true,items},interpretation:{summary:'기본 구현은 안정적으로 해결했고, 그래프 문제에서는 경계 조건을 점검하는 습관이 보였습니다.',uncertainty:'건너뛴 중급 그래프 문항은 평가하지 않았습니다.',observations:[observation]},corrections:[]},{id:'partial',createdAt:'2026-09-30T09:00:00Z',status:'FACTS_ONLY',facts:{complete:false,items:items.map((i,n)=>n<2?i:{...i,status:'OPEN',attempts:0})},corrections:[]}];
  let posts=0;
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let data=[];
    if(path==='/api/me')data={id:'learner',username:'learner',nickname:'연습'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
    if(path==='/api/diagnostics')data=[session];
    if(path==='/api/diagnostics/banks')data=[{id:'algo-mix-a-v1',categories:['arrays-strings','basic-data-structures','bfs','dfs','backtracking','dp','binary-search','greedy','graph','mst'],questionCount:20}];
    if(path==='/api/diagnostics/report-session')data=session;
    if(path.endsWith('/evaluations')){data=reports;if(route.request().method()==='POST'){posts++;data=reports[0];}}
    if(path.endsWith('/profile'))data={categories:[{id:'implementation',selected:true,items:items.slice(0,2),observations:[{...observation,index:0,repeated:false}],alsoSeen:[],ruleIds:[]},{id:'graph',selected:true,items:items.slice(2),observations:[],alsoSeen:[],ruleIds:[]},{id:'dp',selected:false,items:[],observations:[],alsoSeen:[],ruleIds:[]}],rules:[],ruleOnboardingEnabled:false};
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'선택 진단',exact:true}).click();
  await expect(page.getByRole('button',{name:'선택한 20문항 시작'})).toBeVisible();
  await checkPanel();
  await expect(page.locator('.diagnostic-panel fieldset')).toHaveCSS('background-color','rgb(34, 51, 77)');
  await page.screenshot({path:`/tmp/gamjaoj-diagnostic-selection-${width}.png`,fullPage:true});
  await page.getByText('지난 진단',{exact:true}).click();await page.getByRole('button',{name:'4문항 · 완료'}).click();
  await expect(page.getByRole('heading',{name:'진단 결과',exact:true})).toBeVisible();
  await expect(page.getByText(reports[0].interpretation.summary)).toBeVisible();
  await checkPanel();
  await page.getByText('코드 근거·정정·학습 계획',{exact:true}).click();await page.getByText('이 해석에 의견 남기기',{exact:true}).click();
  await page.getByLabel('관찰 1 정정 설명').fill('다음 평가에서도 확인하고 싶은 부분입니다.');
  await page.getByLabel('평가 기록',{exact:true}).selectOption('partial');
  await expect(page.getByRole('table')).toBeVisible();await expect(page.getByText(reports[0].interpretation.summary)).toBeHidden();
  await page.getByLabel('평가 기록',{exact:true}).selectOption('latest');
  await expect(page.getByLabel('관찰 1 정정 설명')).toHaveValue('다음 평가에서도 확인하고 싶은 부분입니다.');
  expect(posts).toBe(0);
  await page.getByRole('heading',{name:'진단 결과',exact:true}).scrollIntoViewIfNeeded();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.screenshot({path:`/tmp/gamjaoj-diagnostic-report-${width}.png`,fullPage:true});
  await page.getByRole('button',{name:'관찰 1 근거·학습 계획 보기'}).click();
  await expect(page.locator('#diagnostic-observation-latest-0')).toBeFocused();
  await page.screenshot({path:`/tmp/gamjaoj-diagnostic-evidence-${width}.png`,fullPage:true});
  await page.getByRole('button',{name:'문항별 진행과 제출 기록',exact:true}).click();
  await expect(page.getByRole('dialog',{name:'진단 기록'})).toBeVisible();await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog',{name:'진단 기록'})).toBeHidden();
  await page.evaluate(()=>document.documentElement.dataset.theme='dark');
  await expect.poll(()=>page.getByRole('button',{name:'다른 진단 보기',exact:true}).evaluate(el=>getComputedStyle(el).backgroundColor===getComputedStyle(document.querySelector('.diagnostic-report')).backgroundColor)).toBe(true);
  await page.getByRole('heading',{name:'진단 결과',exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:`/tmp/gamjaoj-diagnostic-report-dark-${width}.png`,fullPage:true});
});
