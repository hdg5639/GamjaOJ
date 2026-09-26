import {test,expect} from '@playwright/test';
const options=[
  {id:'sequences',label:'수열·수치',template:'sequence-sum-v1',contract:'수열 합',tags:[{id:'basics',label:'기초 순회'},{id:'overflow',label:'정수 범위·오버플로'},{id:'edge-cases',label:'경계값·테스트 설계'}]},
  {id:'strings',label:'문자열·상태',template:'parentheses-v1',contract:'올바른 괄호',tags:[{id:'basics',label:'기초 순회'},{id:'prefix-balance',label:'접두 구간·균형'},{id:'edge-cases',label:'경계값·테스트 설계'}]}
];
options[0].tags.push(
  {id:'filter-positive',label:'양수 선택',group:'선택 조건'},
  {id:'filter-negative',label:'음수 선택',group:'선택 조건'},
  {id:'filter-even',label:'짝수 선택',group:'선택 조건'},
  {id:'filter-odd',label:'홀수 선택',group:'선택 조건'},
  {id:'absolute-values',label:'절댓값',group:'값 변환'},
  {id:'squares',label:'제곱',group:'값 변환'},
  {id:'count',label:'개수 세기',group:'집계'});
options.push({id:'graphs',label:'그래프·경로',template:'graph-recipe-v1-U-UNIT-DISTANCE',contract:'무방향 그래프 · 최단 거리',tags:[
  {id:'basics',label:'기초 순회'}, {id:'overflow',label:'정수 범위·오버플로'}, {id:'edge-cases',label:'경계값·테스트 설계'},
  {id:'directed',label:'방향 간선',group:'그래프 조건'}, {id:'weighted',label:'가중치·0 비용',group:'그래프 조건'},
  {id:'reachable-count',label:'도달 정점 수',group:'질의'}, {id:'max-distance',label:'최대 최단 거리',group:'질의'}]});
function resolveFixture(url){
  const params=new URL(url).searchParams,strings=params.get('category')==='strings',recipe=params.get('tags')?.includes('squares');
  if(params.get('category')==='graphs')return {template:'graph-recipe-v1-D-W-MAX',title:'방향 그래프 · 가중 최대 최단 거리',statement:'도달 불가능한 정점은 제외한다. 입력 규칙 fixture'};
  return {template:strings?'parentheses-v1':recipe?'sequence-recipe-v1-ODD-SQUARE-SUM':'sequence-sum-v1',title:strings?'올바른 괄호':recipe?'홀수의 제곱 합':'수열 합',statement:'입력 규칙 fixture'};
}
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18781';
for(const template of ['sequence-sum-v1','parentheses-v1']) for(const width of [390,1440]) test('personal generation '+template+' is available without operator permission at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let jobs=[],created=false;
  await page.route('**/api/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    let data=[];
    if(path==='/api/generation/selection')data=resolveFixture(request.url());
    if(path==='/api/generation/options')data=options;
    if(path==='/api/me')data={id:'personal-user',username:'learner',nickname:'연습',trainingGoal:''};
    if(path==='/api/ai/status')data={operator:false,enabled:false};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true},
      ...(created?[{version:'generated-personal',title:'내 경계값 연습',statement:'음수를 포함한 합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}]:[])];
    if(path==='/api/generation/learning-context')data=[{id:'analysis-1',summary:new URL(request.url()).searchParams.get('template')==='parentheses-v1'?'전체 개수와 접두 구간의 차이를 연습하세요':'누적 합의 오버플로를 연습하세요'}];
    if(path==='/api/generation'){
      if(request.method()==='POST'){
        expect(request.postDataJSON()).toEqual({shared:true,category:template==='parentheses-v1'?'strings':'sequences',tags:['basics','edge-cases'],sourceAnalysisId:'analysis-1'});
        expect(request.headers()['idempotency-key']).toBeTruthy();
        created=true;jobs=[{id:'job-1',status:'READY',preview:{structure:{category:'수열·수치',reused:true}},problemVersion:'generated-personal',artifacts:{title:'내 경계값 연습',context:'음수를 포함한 합',reference:'SECRET_SOLUTION'}}];
        data=jobs[0];
      }else data=jobs;
    }
    if(path.startsWith('/api/ai/tasks'))throw new Error('Stored analysis must not trigger a new paid analysis');
    if(path==='/api/ai/budget')throw new Error('Personal generation must not request operator budget');
    await route.fulfill({json:data});
  });
  await page.goto(base);
  await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByLabel('카테고리',{exact:true}).selectOption(template==='parentheses-v1'?'strings':'sequences');
  if(template==='parentheses-v1'){
    await expect(page.getByRole('checkbox',{name:'정수 범위·오버플로'})).toHaveCount(0);
    await expect(page.getByRole('checkbox',{name:'접두 구간·균형'})).toBeVisible();
  }
  await page.getByRole('checkbox',{name:'경계값·테스트 설계'}).check();
  await expect(page.getByText('선택한 조합의 규칙을 확인하고 있어요…')).toHaveCount(0);
  await page.getByLabel('반영할 풀이 분석',{exact:true}).selectOption('analysis-1');
  await page.screenshot({path:'/tmp/gamja-generation-'+template+'-'+width+'.png',fullPage:true});
  await page.getByRole('button',{name:'내 연습 문제 만들기'}).click();
  await expect(page.getByText('풀이 준비 완료')).toBeVisible();
  await expect(page.getByText('내 검증 구조 활용',{exact:false})).toBeVisible();
  await expect(page.getByText('SECRET_SOLUTION')).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'이 문제 풀기'}).click();
  await expect(page.getByLabel('풀이할 문제')).toHaveValue('generated-personal');
});

test('theme budget and failure states explain waiting and permit explicit retry',async({page})=>{
  await page.setViewportSize({width:390,height:900});
  let state='HELD_BUDGET',retried=false;
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;
    let data=[];
    if(path==='/api/generation/selection')data=resolveFixture(route.request().url());
    if(path==='/api/generation/options')data=options;
    if(path==='/api/me')data={id:'theme-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/job-theme/retry-theme'){expect(route.request().method()).toBe('POST');retried=true;state='RUNNING';}
    if(path==='/api/generation')data=[{id:'job-theme',status:state==='UNKNOWN'?'THEME_FAILED':'QUEUED',theme:{status:state,domain:'우주 관측',result:null,error:null}}];
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await expect(page.getByText('테마 API 예산 대기',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeDisabled();
  state='UNKNOWN';
  await expect(page.getByRole('button',{name:'소재 준비 다시 요청'})).toBeVisible({timeout:10000});
  await expect(page.getByText('추가 API 비용이 발생할 수 있습니다.',{exact:false})).toBeVisible();
  await page.getByRole('button',{name:'소재 준비 다시 요청'}).click();
  await expect(page.getByText('새 소재 구상 중',{exact:true})).toBeVisible();expect(retried).toBeTruthy();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});

test('category recovery, tag reset and uncertain request preserve the selected contract',async({page})=>{
  let optionCalls=0,posts=[];
  await page.route('**/api/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    let data=[];
    if(path==='/api/generation/selection')data=resolveFixture(route.request().url());
    if(path==='/api/me')data={id:'tag-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options'){
      if(!optionCalls++)return route.fulfill({status:503,json:{message:'잠시 후 다시 시도'}});
      data=options;
    }
    if(path==='/api/generation'&&request.method()==='POST'){
      posts.push({key:request.headers()['idempotency-key'],body:request.postDataJSON()});
      if(posts.length===1)return route.fulfill({status:503,json:{message:'응답 확인 필요'}});
      data={id:'confirmed',status:'QUEUED'};
    }
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeDisabled();
  await page.getByRole('button',{name:'카테고리 다시 불러오기'}).click();
  await page.getByRole('checkbox',{name:'기초 순회'}).uncheck();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeDisabled();
  await page.getByRole('checkbox',{name:'정수 범위·오버플로'}).check();
  await page.getByLabel('카테고리',{exact:true}).selectOption('strings');
  await expect(page.getByRole('checkbox',{name:'기초 순회'})).toBeChecked();
  await expect(page.getByRole('checkbox',{name:'정수 범위·오버플로'})).toHaveCount(0);
  await page.getByRole('checkbox',{name:'접두 구간·균형'}).check();
  await page.getByRole('checkbox',{name:'검증 완료 후 다른 회원에게 공개'}).uncheck();
  await page.getByRole('button',{name:'내 연습 문제 만들기'}).click();
  await expect(page.getByLabel('카테고리',{exact:true})).toBeDisabled();
  await expect(page.getByRole('checkbox',{name:'검증 완료 후 다른 회원에게 공개'})).toBeDisabled();
  await expect(page.getByRole('checkbox',{name:'접두 구간·균형'})).toBeDisabled();
  await page.getByRole('button',{name:'기존 생성 요청 확인'}).click();
  await expect.poll(()=>posts.length).toBe(2);
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeEnabled();
  expect(posts[0]).toEqual(posts[1]);
  expect(posts[0].body).toEqual({shared:false,category:'strings',tags:['basics','prefix-balance']});
});

test('composed recipe preview blocks conflicts and follows the latest selection',async({page})=>{
  await page.setViewportSize({width:390,height:900});
  let slowReply,submitted;
  await page.route('**/api/**',async route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;
    let data=[];
    if(path==='/api/me')data={id:'recipe-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=options;
    if(path==='/api/generation/selection'){
      const tags=url.searchParams.get('tags');
      if(tags.includes('squares')&&tags.includes('count'))return route.fulfill({status:400,json:{message:'개수 세기에는 값 변환 태그를 함께 사용할 수 없어요.'}});
      if(tags==='basics,filter-odd'){slowReply=()=>route.fulfill({json:{template:'stale',title:'지난 규칙',statement:'old'}});return;}
      data=resolveFixture(request.url());
    }
    if(path==='/api/generation'&&request.method()==='POST'){submitted=request.postDataJSON();data={id:'recipe',status:'QUEUED'};}
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('checkbox',{name:'홀수 선택'}).check();
  await expect.poll(()=>!!slowReply).toBeTruthy();
  await page.getByRole('checkbox',{name:'제곱',exact:true}).check();
  await expect(page.getByText('출제 규칙: 홀수의 제곱 합')).toBeVisible();
  await slowReply();await expect(page.getByText('지난 규칙')).toHaveCount(0);
  await page.getByRole('checkbox',{name:'개수 세기'}).check();
  await expect(page.getByRole('alert').filter({hasText:'값 변환 태그'})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeDisabled();
  await page.getByRole('checkbox',{name:'개수 세기'}).uncheck();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeEnabled();
  await page.screenshot({path:'/tmp/gamja-recipe-mobile.png',fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'내 연습 문제 만들기'}).click();
  await expect.poll(()=>submitted).toEqual({shared:true,category:'sequences',tags:['basics','filter-odd','squares']});
});

for(const width of [390,1440])test('graph choices retain query semantics and private request at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});let submitted;
  await page.route('**/api/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'graph-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=options;
    if(path==='/api/generation/selection'){
      const tags=new URL(request.url()).searchParams.get('tags');
      if(tags.includes('reachable-count')&&tags.includes('max-distance'))return route.fulfill({status:400,json:{message:'그래프 질의는 하나만 선택해 주세요.'}});
      data=resolveFixture(request.url());
    }
    if(path==='/api/generation'&&request.method()==='POST'){submitted=request.postDataJSON();data={id:'graph',status:'QUEUED'};}
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByLabel('카테고리',{exact:true}).selectOption('graphs');
  await expect(page.getByRole('checkbox',{name:'제곱',exact:true})).toHaveCount(0);
  await page.getByRole('checkbox',{name:'방향 간선'}).check();await page.getByRole('checkbox',{name:'가중치·0 비용'}).check();
  await page.getByRole('checkbox',{name:'최대 최단 거리',exact:true}).check();
  await page.getByRole('checkbox',{name:'도달 정점 수',exact:true}).check();
  await expect(page.getByRole('alert').filter({hasText:'질의는 하나'})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeDisabled();
  await page.getByRole('checkbox',{name:'도달 정점 수',exact:true}).uncheck();
  await page.getByText('출제 규칙: 방향 그래프 · 가중 최대 최단 거리').click();
  await expect(page.getByText('도달 불가능한 정점은 제외한다. 입력 규칙 fixture')).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기'})).toBeEnabled();
  await page.screenshot({path:'/tmp/gamja-graph-'+width+'.png',fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'내 연습 문제 만들기'}).click();
  await expect.poll(()=>submitted).toEqual({shared:true,category:'graphs',tags:['basics','directed','max-distance','weighted']});
});

for(const width of [390,1440]) test('rule recommendations preserve tags and require explicit apply at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let calls=0,posted=null;
  await page.route('**/api/**',async route=>{
    const request=route.request(),url=new URL(request.url()),path=url.pathname;
    let data=[];
    if(path==='/api/me')data={id:'recommend-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=options;
    if(path==='/api/generation/selection')data=resolveFixture(request.url());
    if(path==='/api/generation/recommendations'){
      calls++;
      expect(url.searchParams.get('tags')).toBe('basics,edge-cases');
      if(calls===1)return route.fulfill({status:503,json:{message:'잠시 후 다시 시도해 주세요.'}});
      data={alternativeAvailable:true,suggestions:[{template:'sequence-recipe-v1-ODD-SQUARE-SUM',title:'홀수의 제곱 합',statement:'원래 값이 홀수인 항을 선택하여 제곱 합을 구한다.',tags:['basics','edge-cases','filter-odd','squares'],recentCount:0}]};
    }
    if(path==='/api/generation'&&request.method()==='POST'){
      posted=request.postDataJSON();data={id:'recommend-job',status:'QUEUED'};
    }
    if(path.startsWith('/api/ai/tasks'))throw new Error('Recommendations must not call paid analysis');
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('checkbox',{name:'경계값·테스트 설계'}).check();
  const recommend=page.getByRole('button',{name:'다른 규칙 추천',exact:true});
  await recommend.click();
  await expect(page.getByText('추천을 불러오지 못했어요:',{exact:false})).toBeVisible();
  await expect(page.getByRole('checkbox',{name:'경계값·테스트 설계'})).toBeChecked();
  await recommend.click();
  await expect(page.getByText('홀수의 제곱 합 · 최근 0회')).toBeVisible();
  await expect(page.getByRole('checkbox',{name:'홀수 선택'})).not.toBeChecked();
  expect(posted).toBeNull();
  await page.screenshot({path:'/tmp/gamja-recommend-'+width+'.png',fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'홀수의 제곱 합 적용'}).click();
  await expect(page.getByRole('checkbox',{name:'홀수 선택'})).toBeChecked();
  await expect(page.getByRole('checkbox',{name:'제곱',exact:true})).toBeChecked();
  await expect(page.getByRole('checkbox',{name:'경계값·테스트 설계'})).toBeChecked();
  await page.getByRole('button',{name:'내 연습 문제 만들기'}).click();
  await expect.poll(()=>posted).toEqual({shared:true,category:'sequences',tags:['basics','edge-cases','filter-odd','squares']});
});

test('late recommendations cannot overwrite a new category',async({page})=>{
  let release,started=false;
  const response=new Promise(resolve=>release=resolve);
  await page.route('**/api/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname;
    let data=[];
    if(path==='/api/me')data={id:'recommend-race',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=options;
    if(path==='/api/generation/selection')data=resolveFixture(request.url());
    if(path==='/api/generation/recommendations'){
      started=true;await response;
      data={alternativeAvailable:true,suggestions:[{template:'old',title:'STALE 추천',statement:'old',tags:['squares'],recentCount:0}]};
    }
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('button',{name:'다른 규칙 추천',exact:true}).click();
  await expect.poll(()=>started).toBeTruthy();
  await page.getByLabel('카테고리',{exact:true}).selectOption('strings');
  const completed=page.waitForResponse('**/api/generation/recommendations?**');
  release();await completed;
  await expect(page.getByLabel('카테고리',{exact:true})).toHaveValue('strings');
  await expect(page.getByText('STALE 추천',{exact:false})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'다른 규칙 추천',exact:true})).toBeEnabled();
});

for(const width of [390,768,1440])test('creation modes preserve inputs and collapse older results at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});let mutations=0;
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(req.method()==='POST')mutations++;
    if(path==='/api/me')data={id:'layout-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=options;
    if(path==='/api/generation/selection')data=resolveFixture(req.url());
    if(path==='/api/generation')data=[{id:'latest',status:'READY',artifacts:{title:'최근 문제',context:'최근 내용'}},{id:'older',status:'READY',artifacts:{title:'이전 문제 제목 '.repeat(12),context:'접혀 있는 과거 내용'}}];
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('checkbox',{name:'경계값·테스트 설계'}).check();
  await expect(page.getByText('접혀 있는 과거 내용',{exact:true})).toBeHidden();
  await page.getByRole('button',{name:'직접 요청하기',exact:true}).click();
  await page.getByLabel('원하는 문제',{exact:true}).fill('문자열 상태 복원을 연습하는 문제');
  await page.getByRole('button',{name:'태그로 만들기',exact:true}).click();
  await expect(page.getByRole('checkbox',{name:'경계값·테스트 설계'})).toBeChecked();
  await page.locator('.generation-job > summary').filter({hasText:'이전 문제 제목'}).click();
  await expect(page.getByText('접혀 있는 과거 내용',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'직접 요청하기',exact:true}).click();
  await expect(page.getByLabel('원하는 문제',{exact:true})).toHaveValue('문자열 상태 복원을 연습하는 문제');
  await page.getByRole('link',{name:'진행·결과로 이동'}).click();
  await expect(page.locator('#request-generation-results')).toBeInViewport();
  expect(mutations).toBe(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:`/tmp/gamja-genux-request-${width}.png`,fullPage:true});
});
