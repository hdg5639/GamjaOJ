import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18781';
for(const width of [390,1440]) test('experimental drafts preserve requests and only open after publication at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let posts=[],items=[],builds=[],reviews=[],publications=[];
  const spec={title:'동일 장비를 중복 선택할 수 없는 탐사 계획',category:'동적 계획법',tags:['0/1 배낭'],statement:'정해진 무게 안에서 장비 가치를 최대화한다.',inputDefinition:'N W와 N개의 무게·가치',outputDefinition:'최대 가치',constraints:'N <= 20, W <= 100',referenceStrategy:'역방향 용량 갱신',oracleStrategy:'N <= 12에서 모든 부분집합 열거',boundaryClasses:['한 장비','모두 무거움'],mutantIdeas:['순방향 갱신으로 중복 선택'],samples:[{input:'1 2\n1 3',output:'3',explanation:'한 번만 선택한다.'},{input:'1 1\n2 3',output:'0',explanation:'선택 불가'}]};
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;let data=[];
    if(path==='/api/me')data={id:'draft-user',username:'learner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/options')data=[{id:'sequences',label:'수열·수치',template:'sequence-sum-v1',tags:[{id:'basics',label:'기초 순회'}]}];
    if(path==='/api/generation/selection')data={template:'sequence-sum-v1',title:'수열 합',statement:'합을 구한다.'};
    if(path==='/api/generation/spec-drafts'){
      if(req.method()==='POST'){
        posts.push({body:req.postDataJSON(),key:req.headers()['idempotency-key']});
        if(posts.length===1)return route.fulfill({status:503,json:{message:'응답을 확인하지 못했어요.'}});
        items=[{id:'draft-1',status:'QUEUED',request:posts[0].body.request,spec:null}];data=items[0];
      }else data=items;
    }
    if(path==='/api/generation/spec-drafts/draft-1/build'){builds.push(req.postDataJSON());items=[{...items[0],status:'CHECKING'}];data=items[0];}
    if(path==='/api/generation/spec-drafts/draft-1/review'){reviews.push(req.postDataJSON());items=[{...items[0],status:'REVIEW_CHECKING'}];data=items[0];}
    if(path==='/api/generation/spec-drafts/draft-1/publish'){publications.push(req.postDataJSON());items=[{...items[0],status:'FINAL_CHECKING'}];data=items[0];}
    if(path==='/api/generation'&&req.method()==='POST')throw new Error('Draft must not create a published problem');
    await route.fulfill({json:data});
  });
  await page.goto(base);await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('button',{name:'직접 요청하기',exact:true}).click();
  await page.getByLabel('원하는 문제',{exact:true}).fill('DP로 같은 장비를 두 번 선택하면 틀리는 문제');
  await page.getByRole('button',{name:'문제 초안 작성',exact:true}).click();
  await expect(page.getByLabel('원하는 문제',{exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'기존 초안 요청 확인'}).click();
  await expect.poll(()=>posts.length).toBe(2);expect(posts[0]).toEqual(posts[1]);
  await expect(page.getByText('초안 작성 대기',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기',includeHidden:true})).toBeDisabled();
  items=[{...items[0],status:'DRAFT_READY',spec,specHash:'fixed-spec-hash'}];
  await expect(page.getByText('초안 저장 완료 · 검증 전',{exact:true})).toBeVisible({timeout:10000});
  await page.getByText('명세와 검증 계획 보기',{exact:true}).click();
  await expect(page.getByText('예제 · 아직 실행 검증되지 않음',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'이 문제 풀기'})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기',includeHidden:true})).toBeEnabled();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'코드 작성·예비 검사',exact:true}).click();
  await expect.poll(()=>builds).toEqual([{specHash:'fixed-spec-hash'}]);
  await expect(page.getByText('Runner 예제·입력 대조 중',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기',includeHidden:true})).toBeDisabled();
  items=[{...items[0],status:'CHECKED',checks:{executions:13,publishable:false}}];
  await expect(page.getByText('예비 실행 검사 통과 · 게시 전',{exact:true})).toBeVisible({timeout:10000});
  await expect(page.getByRole('button',{name:'이 문제 풀기'})).toHaveCount(0);
  await expect(page.getByText('전수·오답 구분·최종 게시 검사는 아직 남아 있어 풀 수 없습니다.',{exact:false})).toBeVisible();
  await page.getByRole('button',{name:'의미·오답 검증',exact:true}).click();
  await expect.poll(()=>reviews).toEqual([{specHash:'fixed-spec-hash'}]);
  await expect(page.getByText('Runner 경계·잘못된 입력·오답 검사 중',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'내 연습 문제 만들기',includeHidden:true})).toBeDisabled();
  items=[{...items[0],status:'REVIEW_CHECKED',review:{executions:6,issues:[],publishable:false}}];
  await expect(page.getByText('독립 검토·오답 검사 통과 · 미게시',{exact:true})).toBeVisible({timeout:10000});
  await expect(page.getByRole('button',{name:'이 문제 풀기'})).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.getByRole('button',{name:'최종 검증·내 문제로 게시',exact:true}).click();
  await expect.poll(()=>publications).toEqual([{specHash:'fixed-spec-hash'}]);
  await expect(page.getByText('Runner 최종 검증 중',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'이 문제 풀기'})).toHaveCount(0);
  items=[{...items[0],status:'PUBLISHED',problemVersion:'v1',publication:{domainCases:4,executions:24,publishable:true}}];
  await expect(page.getByText('내 문제에 게시됨 · 실험 문제',{exact:true})).toBeVisible({timeout:10000});
  await expect(page.getByRole('button',{name:'이 문제 풀기'})).toBeEnabled();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.screenshot({path:'/tmp/gamja-final-'+width+'.png',fullPage:true});
  await page.getByRole('button',{name:'이 문제 풀기'}).click();
  if(width<600)await page.getByRole('button',{name:'코드 작성',exact:true}).click();
  await expect(page.getByRole('button',{name:'제출 후 채점하기',exact:true})).toBeVisible();
});

for(const width of [390,1440]) test('owner holds a published problem and keeps records at '+width,async({page})=>{
  await page.setViewportSize({width,height:900});
  let held=false,attempts=0;
  const spec={title:'검토할 문제',category:'수열',tags:[],statement:'합',samples:[],boundaryClasses:[],mutantIdeas:[]};
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let data=[];
    if(path==='/api/me')data={id:'hold-owner',username:'owner',nickname:'연습'};
    if(path==='/api/problems')data=[{version:'held-v1',title:'검토할 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:!held,problemHeld:held,reviewReason:held?'예제 출력 오류':null}];
    if(path==='/api/generation/spec-drafts')data=[{id:'draft',status:'PUBLISHED',spec,problemVersion:'held-v1',problemHeld:held,reviewReason:held?'예제 출력 오류':null}];
    if(path==='/api/problems/held-v1/review-hold'){
      expect(route.request().postDataJSON()).toEqual({reason:'예제 출력 오류'});
      if(++attempts===1)return route.fulfill({status:503,json:{message:'연결을 다시 확인해 주세요.'}});
      held=true;data={held:true,reason:'예제 출력 오류'};
    }
    await route.fulfill({json:data});
  });
  await page.goto(base+'/#practice');
  await page.getByLabel('Main.java',{exact:true}).fill('// 보존할 초안');
  await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  await page.getByRole('button',{name:'직접 요청하기',exact:true}).click();
  await page.getByText('문제 오류 신고·풀이 보류',{exact:true}).click();
  await page.getByLabel('검토 사유').fill('예제 출력 오류');
  await page.getByRole('button',{name:'이 문제 풀이 보류',exact:true}).click();
  await expect(page.getByLabel('검토 사유')).toHaveValue('예제 출력 오류');
  await page.getByRole('button',{name:'이 문제 풀이 보류',exact:true}).click();
  await expect(page.getByText('문제 검토 중 · 새 풀이 보류',{exact:true})).toBeVisible();
  await expect(page.getByRole('button',{name:'이 문제 풀기',exact:true})).toHaveCount(0);
  await page.getByRole('button',{name:'문제 탐색',exact:true}).click();
  await expect(page.getByRole('region',{name:'문제 목록',exact:true}).getByText('현재 풀이할 수 있는 문제가 없어요.')).toBeVisible();
  await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
  await expect(page.getByRole('button',{name:'제출 후 채점하기',exact:true})).toBeDisabled();
  await expect(page.getByLabel('Main.java',{exact:true})).toContainText('// 보존할 초안');
  await expect(page.getByRole('button',{name:'코드 실행',exact:true})).toBeDisabled();
  await page.screenshot({path:`/tmp/gamja-hold-${width}.png`});
});
