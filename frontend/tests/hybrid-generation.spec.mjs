import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18781';
const options={enabled:true,message:'검증을 통과한 문제만 게시합니다.',profiles:[{id:'zero-one-items-v1',label:'0/1 배낭 · 물건 선택',description:'규칙은 고정하고 새 본문·코드·힌트·해설과 테스트를 만듭니다.',rules:['각 물건은 최대 한 번 선택합니다.','물건 1~100개 · 한도 1~1,000','가치의 합을 최대화합니다.']}]};
const makeJob=(id,status='BUILDING')=>({id,status,shared:false,acceptedAt:new Date().toISOString(),deadlineAt:new Date(Date.now()+120000).toISOString(),branches:{CONTRACT:'SUCCEEDED',CORE:'QUEUED',PRESENTATION:'QUEUED',READER:'NOT_STARTED',VALIDATION:'NOT_STARTED',CONTENT_REVIEW:'NOT_STARTED'},publishedVersionId:status==='PUBLISHED'?'hybrid-check-fixture':null});
async function fixture(page,custom){
  await page.route('**/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname;
    if(custom&&await custom(route,path,req))return;
    let data=[];
    if(path==='/api/me')data={id:'hybrid-user',username:'learner',nickname:'연습'};
    if(path==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
    if(path==='/api/problems')data=[{version:'v1',title:'기본 문제',statement:'합',sampleInput:'1',sampleOutput:'1',submissionsEnabled:true},{version:'hybrid-check-fixture',title:'완성된 배낭 문제',statement:'물건을 한 번씩 선택하세요.',sampleInput:'1 1\n1 1',sampleOutput:'1',submissionsEnabled:true}];
    if(path==='/api/generation/hybrid/options')data=options;
    await route.fulfill({json:data});
  });
  await page.goto(base+'/#generation');
  await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
}
for(const width of [390,768,1440])test('explicit consent creates once, restores progress and opens the published problem at '+width,async({page})=>{
  await page.setViewportSize({width,height:950});const writes=[];let jobs=[];
  await fixture(page,async(route,path,req)=>{
    if(path==='/api/generation/hybrid'){
      if(req.method()==='POST'){writes.push({key:req.headers()['idempotency-key'],body:req.postDataJSON()});jobs=[makeJob(writes[0].key)];await route.fulfill({json:jobs[0]});}
      else await route.fulfill({json:jobs});return true;
    }
  });
  const create=page.getByRole('button',{name:'이 규칙으로 생성·게시',exact:true});
  await expect(create).toBeDisabled();await expect(page.getByRole('checkbox',{name:'다른 회원에게도 공개',exact:true})).not.toBeChecked();expect(writes).toHaveLength(0);
  const consent=page.getByRole('checkbox',{name:'위 규칙으로 생성하고 검증 통과 시 게시',exact:true});
  await expect(consent).toBeEnabled();await consent.focus();await page.keyboard.press('Space');await expect(consent).toBeChecked();await expect(create).toBeEnabled();
  expect(await consent.evaluate(e=>getComputedStyle(e).outlineStyle)).not.toBe('none');
  await page.screenshot({path:`/tmp/gamja-hybrid-create-${width}.png`,fullPage:true});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await create.click();await expect(page.getByText('문제 작성 중',{exact:true})).toBeVisible();
  expect(writes).toHaveLength(1);expect(writes[0].body).toEqual({profileId:'zero-one-items-v1',shared:false,publishOnSuccess:true});
  await page.reload();await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
  await expect(page.getByText('문제 작성 중',{exact:true})).toBeVisible();expect(writes).toHaveLength(1);
  jobs=[{...makeJob(writes[0].key,'PUBLISHED'),branches:Object.fromEntries(['CONTRACT','CORE','PRESENTATION','READER','VALIDATION','CONTENT_REVIEW'].map(key=>[key,key==='VALIDATION'?'CHECKED':'SUCCEEDED']))}];
  await expect(page.getByRole('button',{name:'이 문제 풀기',exact:true})).toBeVisible({timeout:10000});
  await page.screenshot({path:`/tmp/gamja-hybrid-result-${width}.png`,fullPage:true});
  await page.getByRole('button',{name:'이 문제 풀기',exact:true}).click();
  await expect(page.locator('#problem-title')).toHaveText('완성된 배낭 문제');expect(writes).toHaveLength(1);
});
test('unknown POST outcome preserves the same request across reload and flag disable',async({page})=>{
  const keys=[];let jobs=[],enabled=true;
  await fixture(page,async(route,path,req)=>{
    if(path==='/api/generation/hybrid/options'){await route.fulfill({json:{...options,enabled}});return true;}
    if(path==='/api/generation/hybrid'){
      if(req.method()==='POST'){
        keys.push(req.headers()['idempotency-key']);
        if(keys.length===1){await route.abort('failed');return true;}
        jobs=[makeJob(keys[0])];await route.fulfill({json:jobs[0]});
      }else await route.fulfill({json:jobs});return true;
    }
  });
  await page.getByRole('checkbox',{name:'위 규칙으로 생성하고 검증 통과 시 게시'}).check();
  await page.getByRole('button',{name:'이 규칙으로 생성·게시'}).click();await expect(page.getByRole('button',{name:'기존 요청 확인'})).toBeEnabled();
  enabled=false;await page.reload();await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
  await page.getByRole('button',{name:'기존 요청 확인'}).click();await expect(page.getByText('문제 작성 중',{exact:true})).toBeVisible();
  expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);
  expect(await page.evaluate(()=>sessionStorage.getItem('gamjaoj-hybrid-request-hybrid-user'))).toBeNull();
});
test('disabled admission and read failures keep legacy modes available and never submit',async({page})=>{
  let mutations=0,fail=true;
  await fixture(page,async(route,path,req)=>{
    if(req.method()==='POST')mutations++;
    if(path==='/api/generation/hybrid/options'){await route.fulfill({json:{...options,enabled:false,message:'아직 이 계정에서는 실험 출제를 시작할 수 없어요.'}});return true;}
    if(path==='/api/generation/hybrid'){await route.fulfill(fail?{status:503,json:{message:'연결 오류'}}:{json:[]});return true;}
  });
  await expect(page.getByText('아직 이 계정에서는 실험 출제를 시작할 수 없어요.')).toBeVisible();
  await expect(page.getByRole('button',{name:'이 규칙으로 생성·게시'})).toBeDisabled();
  await expect(page.getByRole('alert').filter({hasText:'연결 오류'})).toBeVisible();fail=false;
  await page.getByRole('button',{name:'상태 다시 확인'}).click();await expect(page.getByText('아직 요청한 문제가 없어요.',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'직접 요청하기',exact:true}).click();await expect(page.getByLabel('원하는 문제',{exact:true})).toBeVisible();expect(mutations).toBe(0);
});
for(const status of ['DEADLINE_EXCEEDED','HELD','CANCELLED'])test('terminal '+status+' does not retry or expose publication',async({page})=>{
  let mutations=0;
  await fixture(page,async(route,path,req)=>{
    if(req.method()==='POST')mutations++;
    if(path==='/api/generation/hybrid'){await route.fulfill({json:[{...makeJob('dead-job',status),error:'CONTENT_REVIEW_REJECTED'}]});return true;}
  });
  await expect(page.locator('.hybrid-generation .generation-job')).toBeVisible();
  await expect(page.getByRole('button',{name:'이 문제 풀기',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'이 요청 취소'})).toHaveCount(0);
  expect(mutations).toBe(0);
});
test('cancel keeps durable terminal record; held published problem cannot open',async({page})=>{
  let job=makeJob('cancel-job'),cancelled=0;
  await fixture(page,async(route,path,req)=>{
    if(path==='/api/generation/hybrid/cancel-job/cancel'){cancelled++;job={...job,status:'CANCELLED'};await route.fulfill({json:job});return true;}
    if(path==='/api/generation/hybrid'){await route.fulfill({json:[job]});return true;}
  });
  await page.getByRole('button',{name:'이 요청 취소'}).click();await expect(page.getByText('요청 취소됨',{exact:true})).toBeVisible();expect(cancelled).toBe(1);
  job={...makeJob('held-job','PUBLISHED'),problemHeld:true};await page.reload();await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
  await expect(page.getByRole('button',{name:'이 문제 풀기',exact:true})).toBeDisabled();await expect(page.getByText('게시 후 검토 보류',{exact:true})).toBeVisible();
});
test('BFS choice resets consent and survives an uncertain request and reload',async({page})=>{
  const bfs={id:'bfs-shortest-path-v1',label:'BFS · 무방향 그래프 최단 거리',description:'최소 간선 수를 구합니다.',rules:['간선은 양방향입니다.','출발점과 도착점이 같으면 0, 경로가 없으면 -1입니다.']};
  const writes=[];
  await page.setViewportSize({width:390,height:950});
  await fixture(page,async(route,path,req)=>{
    if(path==='/api/generation/hybrid/options'){await route.fulfill({json:{...options,profiles:[...options.profiles,bfs]}});return true;}
    if(path==='/api/generation/hybrid'){
      if(req.method()==='POST'){
        writes.push({key:req.headers()['idempotency-key'],body:req.postDataJSON()});
        if(writes.length===1){await route.abort('failed');return true;}
        await route.fulfill({json:{...makeJob(writes[0].key),profileId:bfs.id}});
      }else await route.fulfill({json:[]});return true;
    }
  });
  const consent=page.getByRole('checkbox',{name:'위 규칙으로 생성하고 검증 통과 시 게시',exact:true});
  await consent.check();const select=page.getByRole('combobox',{name:'문제 유형'});
  await select.selectOption(bfs.id);await expect(consent).not.toBeChecked();
  await expect(page.getByRole('button',{name:'이 규칙으로 생성·게시'})).toBeDisabled();
  await expect(page.getByText(bfs.rules[1],{exact:true})).toBeVisible();
  await select.focus();expect(await select.evaluate(e=>getComputedStyle(e).outlineStyle)).not.toBe('none');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:'/tmp/gamja-bfs-selection-390.png',fullPage:true});
  await consent.check();await page.getByRole('button',{name:'이 규칙으로 생성·게시'}).click();
  await expect(page.getByRole('button',{name:'기존 요청 확인'})).toBeEnabled();
  await page.reload();await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
  await expect(select).toHaveValue(bfs.id);await expect(select).toBeDisabled();
  await page.getByRole('button',{name:'기존 요청 확인'}).click();
  await expect(page.locator('.hybrid-generation summary strong')).toHaveText(bfs.label);
  expect(writes).toHaveLength(2);expect(writes[0]).toEqual(writes[1]);
  expect(writes[0].body).toEqual({profileId:bfs.id,shared:false,publishOnSuccess:true});
});

test('Dijkstra choice resets consent and survives an uncertain request and reload',async({page})=>{
  const dijkstra={id:'dijkstra-shortest-path-v1',label:'다익스트라 · 가중치 최단 거리',description:'가중치의 최소 합을 구합니다.',rules:['간선은 양방향이며 비용은 1~1,000,000,000입니다.','출발점과 도착점이 같으면 0, 경로가 없으면 -1입니다.']};
  const writes=[];
  await page.setViewportSize({width:390,height:950});
  await fixture(page,async(route,path,req)=>{
    if(path==='/api/generation/hybrid/options'){await route.fulfill({json:{...options,profiles:[...options.profiles,dijkstra]}});return true;}
    if(path==='/api/generation/hybrid'){
      if(req.method()==='POST'){
        writes.push({key:req.headers()['idempotency-key'],body:req.postDataJSON()});
        if(writes.length===1){await route.abort('failed');return true;}
        await route.fulfill({json:{...makeJob(writes[0].key),profileId:dijkstra.id}});
      }else await route.fulfill({json:[]});return true;
    }
  });
  const consent=page.getByRole('checkbox',{name:'위 규칙으로 생성하고 검증 통과 시 게시',exact:true});
  await consent.check();const select=page.getByRole('combobox',{name:'문제 유형'});
  await select.selectOption(dijkstra.id);await expect(consent).not.toBeChecked();
  await expect(page.getByRole('button',{name:'이 규칙으로 생성·게시'})).toBeDisabled();
  await expect(page.getByText(dijkstra.rules[1],{exact:true})).toBeVisible();
  await select.focus();expect(await select.evaluate(e=>getComputedStyle(e).outlineStyle)).not.toBe('none');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:'/tmp/gamja-dijkstra-selection-390.png',fullPage:true});
  await consent.check();await page.getByRole('button',{name:'이 규칙으로 생성·게시'}).click();
  await expect(page.getByRole('button',{name:'기존 요청 확인'})).toBeEnabled();
  await page.reload();await page.getByRole('button',{name:'규칙 고정 출제 · 실험',exact:true}).click();
  await expect(select).toHaveValue(dijkstra.id);await expect(select).toBeDisabled();
  await page.getByRole('button',{name:'기존 요청 확인'}).click();
  await expect(page.locator('.hybrid-generation summary strong')).toHaveText(dijkstra.label);
  expect(writes).toHaveLength(2);expect(writes[0]).toEqual(writes[1]);
  expect(writes[0].body).toEqual({profileId:dijkstra.id,shared:false,publishOnSuccess:true});
});
