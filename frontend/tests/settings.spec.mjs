import {test,expect} from '@playwright/test';
import {expectSurfaceTint} from './surface-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';

for(const width of [360,768,1440])test(`settings navigation preserves edits and recovers failed profile saves at ${width}`,async({page})=>{
 await page.setViewportSize({width,height:960});
 let user={id:'settings-user',username:'learner',nickname:'알고리즘을연습하는감자',trainingGoal:'DFS 방문 상태 복원'};
 let updates=0;
 await page.route('**/api/**',async route=>{
  const request=route.request(),path=new URL(request.url()).pathname;let data=[];
  if(path==='/api/me'){
   data=user;
   if(request.method()==='PATCH'){
    expect(request.headers()['x-csrf-token']).toBe('fixture');updates++;
    if(updates===1)return route.fulfill({status:503,json:{message:'잠시 후 다시 저장해 주세요.'}});
    user={...user,...request.postDataJSON()};data=user;
   }
  }
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  if(path==='/api/integrations')data=[
   {provider:'GITHUB',available:true,connected:true,status:'CONNECTED',account:'learner',autoEnabled:true,target:{id:'repo',repo:'learner/algorithm-solutions',label:'learner/algorithm-solutions',branch:'main',prefix:'GamjaOJ',private:true,layout:'problem-v1'}},
   {provider:'NOTION',available:true,connected:true,status:'CONNECTED',account:'알고리즘 공부 기록 워크스페이스',autoEnabled:true,target:{id:'parent',label:'나의 문제 풀이와 회고 기록',kind:'notion_table_parent'}}
  ];
  if(path==='/api/integrations/deliveries')data=[{id:'done',provider:'GITHUB',problemVersion:'sum-v1',language:'JAVA',status:'SUCCEEDED',url:'https://github.com/learner/algorithm-solutions'},{id:'failed',provider:'NOTION',problemVersion:'valid-parentheses-v1',language:'CPP',status:'FAILED',error:'PERMISSION_REQUIRED'}];
  return route.fulfill({json:data});
 });
 await page.goto(base+'/?settings=integrations');
 await expect(page.getByRole('heading',{name:'내 설정',exact:true})).toBeVisible();
 const settings=page.getByRole('navigation',{name:'설정 항목'});
 const nickname=page.getByLabel('닉네임'),goal=page.getByLabel('연습하고 싶은 목표');
 await nickname.fill('꾸준히푸는감자');await goal.fill('DP 점화식 세우기');
 await page.screenshot({path:`/tmp/gamja-settings-profile-${width}.png`,fullPage:true});
 await settings.getByRole('link',{name:'풀이 자동 저장',exact:true}).click();
 await expect(page.getByRole('heading',{name:'풀이 자동 저장',exact:true})).toBeInViewport();
 await expect(nickname).toHaveValue('꾸준히푸는감자');
 await page.screenshot({path:`/tmp/gamja-settings-integrations-${width}.png`,fullPage:true});
 await settings.getByRole('link',{name:'최근 저장 내역',exact:true}).click();
 await expect(page.getByRole('heading',{name:'최근 저장 내역',exact:true})).toBeInViewport();
 await settings.getByRole('link',{name:'프로필과 연습 목표',exact:true}).focus();
 await page.keyboard.press('Enter');await expect(page.getByRole('heading',{name:'프로필과 연습 목표',exact:true})).toBeInViewport();
 await page.getByRole('button',{name:'내 설정 저장',exact:true}).click();
 await expect(page.getByRole('region',{name:'내 계정',exact:true}).getByRole('alert')).toContainText('잠시 후 다시 저장해 주세요.');
 await expect(nickname).toHaveValue('꾸준히푸는감자');await expect(goal).toHaveValue('DP 점화식 세우기');
 await page.getByRole('button',{name:'내 설정 저장',exact:true}).click();
 await expect(page.getByRole('region',{name:'내 계정',exact:true}).getByRole('status')).toContainText('내 연습 설정을 저장했어요.');expect(updates).toBe(2);
 await settings.getByRole('link',{name:'계정 관리',exact:true}).click();
 await expect(page.getByRole('button',{name:'계정 영구 삭제'})).not.toBeVisible();
 await page.getByText('회원 탈퇴',{exact:true}).click();await expect(page.getByRole('button',{name:'계정 영구 삭제'})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1&&document.querySelector('.settings-page').scrollWidth<=document.querySelector('.settings-page').clientWidth+1)).toBe(true);
 if(width===1440){
  await page.getByRole('button',{name:'다크 모드로 전환'}).click();await settings.getByRole('link',{name:'풀이 자동 저장',exact:true}).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
  await expectSurfaceTint(page.locator('.export-search .secondary').first(),[22,30,28]);await page.screenshot({path:'/tmp/gamja-settings-dark.png',fullPage:true});
 }
});
