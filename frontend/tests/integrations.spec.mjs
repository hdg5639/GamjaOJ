import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test(`export settings select destination, retry and disconnect at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:900});let saved=null,retries=0,disconnected=false;
 let connections=[{provider:'GITHUB',available:true,connected:true,status:'CONNECTED',account:'learner',target:null,autoEnabled:false},{provider:'NOTION',available:false,connected:false,status:'DISCONNECTED'}];
 let deliveries=[{id:'delivery',provider:'GITHUB',problemVersion:'sum-v1',language:'JAVA',status:'FAILED',error:'PERMISSION_REQUIRED'}];
 await page.route('**/api/**',async route=>{
  const r=route.request(),u=new URL(r.url());let data=[];
  if(u.pathname==='/api/me')data={id:'exports-user',username:'learner',nickname:'학습자',trainingGoal:''};
  if(u.pathname==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  if(u.pathname==='/api/integrations')data=connections;
  if(u.pathname==='/api/integrations/deliveries')data=deliveries;
  if(u.pathname==='/api/integrations/GITHUB/targets')data=[{id:'learner/solutions',label:'learner/solutions',privateTarget:false,branch:'main'}];
  if(u.pathname==='/api/integrations/GITHUB/target'){
   expect(r.headers()['x-csrf-token']).toBe('fixture');saved=r.postDataJSON();connections[0]={...connections[0],autoEnabled:saved.autoEnabled,target:{id:'repo-id',repo:saved.targetId,label:saved.targetId,url:'https://github.com/'+saved.targetId,private:false,branch:saved.branch,prefix:saved.prefix}};
   return route.fulfill({status:204});
  }
  if(u.pathname==='/api/integrations/deliveries/delivery/retry'){retries++;deliveries=[{...deliveries[0],status:'QUEUED',error:null}];return route.fulfill({status:204});}
  if(u.pathname==='/api/integrations/GITHUB'&&r.method()==='DELETE'){disconnected=true;connections[0]={provider:'GITHUB',available:true,connected:false,status:'DISCONNECTED'};return route.fulfill({status:204});}
  return route.fulfill({json:data});
 });
 await page.goto(base+'/?settings=integrations&connected=GITHUB');
 await expect(page.getByRole('heading',{name:'풀이 자동 저장'})).toBeVisible();
 const github=page.getByRole('region',{name:'GitHub 연동'});
 await github.getByRole('button',{name:'목록 불러오기'}).click();await github.getByRole('combobox',{name:'저장 위치',exact:true}).selectOption('learner/solutions');
 await github.getByLabel('앞으로 통과한 풀이 자동 저장').check();await github.getByRole('button',{name:'저장 설정 적용'}).click();
 await expect(github.getByText('저장 위치와 자동 저장 설정을 적용했어요.')).toBeVisible();expect(saved).toEqual({targetId:'learner/solutions',branch:'main',prefix:'GamjaOJ',autoEnabled:true});
 await expect(github.locator('.export-current')).toContainText('공개 저장소');await page.screenshot({path:`/tmp/gamja-integrations-connected-${width}.png`,fullPage:true});await page.getByRole('button',{name:'재시도',exact:true}).click();await expect(page.getByText('저장 대기',{exact:true})).toBeVisible();expect(retries).toBe(1);
 await github.getByRole('button',{name:'연결 해제',exact:true}).click();expect(disconnected).toBe(false);await github.getByRole('button',{name:'취소',exact:true}).click();
 await github.getByRole('button',{name:'연결 해제',exact:true}).click();await github.getByRole('button',{name:'연결 해제 확인'}).click();await expect(github.getByRole('button',{name:'GitHub 연결'})).toBeVisible();expect(disconnected).toBe(true);
 await expect(page.getByRole('region',{name:'Notion 연동'}).getByText('서비스 연동을 준비 중이에요.',{exact:false})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-integrations-${width}.png`,fullPage:true});
});
test('failed settings load offers refresh and preserves the account form',async({page})=>{
 await page.route('**/api/**',route=>{const p=new URL(route.request().url()).pathname;if(p==='/api/me')return route.fulfill({json:{id:'error-user',username:'learner',nickname:'학습자'}});if(p==='/api/integrations')return route.fulfill({status:503,json:{message:'잠시 후 다시 시도해 주세요.'}});return route.fulfill({json:[]});});
 await page.goto(base+'/?settings=integrations');await expect(page.locator('.integrations [role=alert]')).toContainText('잠시 후 다시 시도해 주세요.');await expect(page.getByLabel('닉네임')).toHaveValue('학습자');await expect(page.getByRole('button',{name:'새로고침',exact:true})).toBeEnabled();
});
