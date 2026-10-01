import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
for(const width of [390,1440])test(`independent upload switches preserve destination drafts and recover failures at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:960});let failed=false;const writes=[];
 let connections=[{provider:'GITHUB',available:true,connected:true,status:'CONNECTED',account:'learner',autoEnabled:true,target:{id:'repo',repo:'learner/solutions',label:'learner/solutions',branch:'main',prefix:'GamjaOJ'}},{provider:'NOTION',available:true,connected:true,status:'CONNECTED',account:'학습 공간',autoEnabled:false,target:{id:'parent',label:'풀이 기록',kind:'notion_table_parent'}}];
 await page.route('**/api/**',route=>{
  const r=route.request(),p=new URL(r.url()).pathname;let data=[];
  if(p==='/api/me')data={id:'toggle-user',username:'learner',nickname:'감자'};
  if(p==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  if(p==='/api/integrations')data=connections;
  if(r.method()==='PATCH'){
   expect(r.headers()['x-csrf-token']).toBe('fixture');expect(r.postDataJSON()).toEqual({enabled:!connections.find(c=>p.includes(c.provider)).autoEnabled});writes.push(p);
   if(!failed){failed=true;return route.fulfill({status:503,json:{message:'다시 시도해 주세요.'}});}
   connections=connections.map(c=>p.includes(c.provider)?{...c,autoEnabled:r.postDataJSON().enabled}:c);return route.fulfill({status:204});
  }
  expect(r.method()).not.toBe('PUT');return route.fulfill({json:data});
 });
 await page.goto(base+'/?settings=integrations');const github=page.getByRole('region',{name:'GitHub 연동'}),notion=page.getByRole('region',{name:'Notion 연동'});
 const gh=github.getByRole('switch',{name:'GitHub 자동 업로드'}),nt=notion.getByRole('switch',{name:'Notion 자동 업로드'});
 await expect(gh).toBeChecked();await expect(nt).not.toBeChecked();await github.getByLabel('브랜치',{exact:true}).fill('draft-branch');
 await gh.click();await expect(github.getByRole('alert')).toHaveText('다시 시도해 주세요.');await expect(gh).toBeChecked();
 await gh.click();await expect(gh).not.toBeChecked();await expect(github.getByLabel('브랜치',{exact:true})).toHaveValue('draft-branch');
 await nt.focus();await page.keyboard.press('Space');await expect(nt).toBeChecked();await expect(gh).not.toBeChecked();expect(writes).toHaveLength(3);
 await gh.scrollIntoViewIfNeeded();await page.screenshot({path:`/tmp/gamja-auto-upload-${width}.png`,fullPage:true});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
