import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
async function mock(page){
 let signedIn=false;const calls=[];
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[],status=200;
  if(path==='/api/me'){if(signedIn)data={id:'login-entry',username:'learner',nickname:'연습'};else status=401;}
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/auth/signup'){calls.push({path,body:req.postDataJSON()});status=201;}
  if(path==='/api/auth/login'){signedIn=true;calls.push({path,body:req.postData()});status=204;}
  if(path==='/api/problems')data=[{version:'demo',title:'시작 문제',statement:'설명',submissionsEnabled:true}];
  await route.fulfill(status===204?{status}:{status,json:data});
 });
 return calls;
}
test('desktop wordmark starts centrally, lands at the real brand, and plays once per tab',async({page})=>{
 await page.setViewportSize({width:1440,height:950});await mock(page);await page.goto(base);
 const flight=page.locator('.auth-brand-flight');await expect(flight).toBeVisible();
 const start=await flight.locator('img').boundingBox();expect(Math.abs(start.x+start.width/2-720)).toBeLessThan(8);
 await flight.evaluate(n=>{for(const a of n.getAnimations({subtree:true})){a.pause();a.currentTime=2400;}});
 const landed=await flight.boundingBox(),target=await page.locator('.brand').boundingBox();
 expect(Math.abs(landed.x-target.x)).toBeLessThan(1);expect(Math.abs(landed.y-target.y)).toBeLessThan(1);expect(Math.abs(landed.width-target.width)).toBeLessThan(1);
 await expect(flight).toHaveCount(0);await expect(page.getByLabel('아이디',{exact:true})).toBeVisible();
 await page.reload();await expect(page.getByRole('heading',{name:'다시 만나 반가워요.'})).toBeVisible();await expect(flight).toHaveCount(0);
});
test('keyboard skip, signup validation and login keep the existing auth contract',async({page})=>{
 await page.setViewportSize({width:1440,height:950});const calls=await mock(page);await page.goto(base);await expect(page.locator('.auth-brand-flight')).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('.auth-brand-flight')).toHaveCount(0);
 await page.getByRole('button',{name:'처음 왔어요',exact:true}).click();await page.getByLabel('닉네임',{exact:true}).fill('학습자');await page.getByLabel('아이디',{exact:true}).fill('learner');await page.getByLabel('비밀번호',{exact:true}).fill('password123');await page.getByLabel('비밀번호 확인',{exact:true}).fill('different123');await page.getByRole('button',{name:'가입하기',exact:true}).click();await expect(page.locator('.login-page > .card [role="alert"]')).toContainText('비밀번호가 서로 달라요.');expect(calls).toHaveLength(0);
 await page.getByLabel('비밀번호 확인',{exact:true}).fill('password123');await page.getByRole('button',{name:'가입하기',exact:true}).click();await expect(page.locator('.login-page > .card [role="status"]')).toContainText('가입했어요!');expect(calls[0].body).toEqual({nickname:'학습자',username:'learner',password:'password123'});
 await page.getByLabel('아이디',{exact:true}).fill('learner');await page.getByLabel('비밀번호',{exact:true}).fill('password123');await page.getByRole('button',{name:'내 연습장으로',exact:true}).click();await expect(page.getByRole('button',{name:'로그아웃',exact:true})).toBeVisible();expect(calls[1].body).toBe('username=learner&password=password123');await expect(page.locator('.auth-intro-overlay')).toHaveCount(0);
});
for(const width of [390,768,1440])for(const dark of [false,true])test(`login/signup reflows at ${width}px in ${dark?'dark':'light'}`,async({page})=>{
 await page.setViewportSize({width,height:950});await page.emulateMedia({reducedMotion:'reduce',colorScheme:dark?'dark':'light'});await mock(page);await page.goto(base);
 await expect(page.getByLabel('아이디',{exact:true})).toBeVisible();await expect(page.locator('.auth-intro-overlay')).toHaveCount(0);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.getByLabel('아이디',{exact:true}).focus();await page.keyboard.press('Tab');await expect(page.getByLabel('비밀번호',{exact:true})).toBeFocused();await page.keyboard.press('Tab');await expect(page.getByRole('button',{name:'내 연습장으로',exact:true})).toBeFocused();
 await page.screenshot({path:`/tmp/gamja-login-${dark?'dark':'light'}-${width}.png`,fullPage:true});
 await page.getByRole('button',{name:'처음 왔어요',exact:true}).click();await expect(page.getByLabel('비밀번호 확인',{exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:`/tmp/gamja-signup-${dark?'dark':'light'}-${width}.png`,fullPage:true});
});
test('viewport changes during the intro stop the overlay without losing typed form values',async({page})=>{
 await page.setViewportSize({width:1440,height:950});await mock(page);await page.goto(base);await expect(page.locator('.auth-brand-flight')).toBeVisible();await page.setViewportSize({width:390,height:844});await expect(page.locator('.auth-intro-overlay')).toHaveCount(0);await page.getByLabel('아이디',{exact:true}).fill('learner');await page.setViewportSize({width:1440,height:950});await expect(page.getByLabel('아이디',{exact:true})).toHaveValue('learner');
});
test('Tab skips the intro and keeps the normal keyboard navigation available',async({page})=>{
 await page.setViewportSize({width:1440,height:950});await mock(page);await page.goto(base);await expect(page.locator('.auth-brand-flight')).toBeVisible();await page.keyboard.press('Tab');await expect(page.locator('.auth-intro-overlay')).toHaveCount(0);await expect(page.locator('.brand')).toBeVisible();await page.getByLabel('아이디',{exact:true}).focus();await page.keyboard.press('Tab');await expect(page.getByLabel('비밀번호',{exact:true})).toBeFocused();
});
