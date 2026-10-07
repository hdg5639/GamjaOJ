import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
async function open(page,width,height){
 await page.setViewportSize({width,height});await page.emulateMedia({reducedMotion:'reduce'});
 await page.route('**/api/**',r=>r.fulfill({status:new URL(r.request().url()).pathname==='/api/me'?401:200,json:{}}));
 await page.goto(base);await page.getByLabel('아이디',{exact:true}).waitFor();
}
for(const [width,height] of [[1280,600],[1280,640],[1366,650],[1440,700],[1024,650],[1280,720],[1366,768],[1440,900],[1920,1080],[1024,768],[768,950]])test('login and signup fit '+width+'×'+height,async({page})=>{
 await open(page,width,height);
 for(const mode of ['login','signup']){
  if(mode==='signup')await page.getByRole('button',{name:'처음 왔어요',exact:true}).click();
  const sizes=await page.evaluate(()=>({height:innerHeight,scroll:document.documentElement.scrollHeight,width:innerWidth,scrollWidth:document.documentElement.scrollWidth}));
  expect(sizes.scroll).toBeLessThanOrEqual(sizes.height+1);expect(sizes.scrollWidth).toBeLessThanOrEqual(sizes.width);
  const footer=await page.locator('.shell > footer').boundingBox();expect(footer.y+footer.height).toBeLessThanOrEqual(height+1);
  const lastLine=await page.locator('.shell > footer .site-notice a').boundingBox();expect(lastLine.y+lastLine.height).toBeLessThanOrEqual(height-4);
  const card=await page.locator('.login-page > .card').boundingBox();expect(card.y+card.height).toBeLessThanOrEqual(footer.y);
  const button=page.getByRole('button',{name:mode==='login'?'내 연습장으로':'가입하기',exact:true});const rect=await button.boundingBox();expect(rect.y+rect.height).toBeLessThan(height);
  await page.screenshot({path:'/tmp/gamja-login-fit-'+width+'-'+height+'-'+mode+'.png',fullPage:true});
 }
});
test('small and zoom-sized screens keep form fields and footer reachable without clipping',async({page})=>{
 await open(page,390,600);await page.getByRole('button',{name:'처음 왔어요',exact:true}).click();
 await page.getByLabel('비밀번호 확인',{exact:true}).fill('password123');await expect(page.getByLabel('비밀번호 확인',{exact:true})).toHaveValue('password123');
 await page.getByRole('button',{name:'가입하기',exact:true}).scrollIntoViewIfNeeded();await expect(page.getByRole('button',{name:'가입하기',exact:true})).toBeInViewport();
 await page.locator('footer').scrollIntoViewIfNeeded();await expect(page.locator('footer')).toBeInViewport();
 await page.setViewportSize({width:960,height:540});await page.getByLabel('아이디',{exact:true}).fill('learner');await page.getByRole('button',{name:'가입하기',exact:true}).scrollIntoViewIfNeeded();await expect(page.getByRole('button',{name:'가입하기',exact:true})).toBeInViewport();await expect(page.getByLabel('아이디',{exact:true})).toHaveValue('learner');
});
