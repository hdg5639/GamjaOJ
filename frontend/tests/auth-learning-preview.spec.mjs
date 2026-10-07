import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
async function open(page,motion='no-preference'){
 await page.setViewportSize({width:1440,height:1000});
 await page.emulateMedia({reducedMotion:motion});
 await page.route('**/api/**',route=>route.fulfill({status:new URL(route.request().url()).pathname==='/api/me'?401:200,json:{}}));
 await page.goto(base);await page.keyboard.press('Escape');
 return page.getByRole('region',{name:'GamjaOJ 연습 흐름 예시'});
}
test('automatic scenes keep their bounds, pause on hover and stop when login input receives focus',async({page})=>{
 await page.clock.install();const preview=await open(page);
 const active=()=>preview.locator('.auth-preview-slide[data-active="true"]');
 await expect(active()).toContainText('내 방식으로 풀고');const before=await preview.boundingBox();
 await page.clock.fastForward(7100);await expect(active()).toContainText('한 번 더 돌아보고');
 const after=await preview.boundingBox();expect(after.height).toBe(before.height);expect(after.width).toBe(before.width);
 await preview.hover();await page.clock.fastForward(15000);await expect(active()).toContainText('한 번 더 돌아보고');
 await page.mouse.move(0,0);await page.clock.fastForward(7100);await expect(active()).toContainText('다음 연습으로 이어가요');
 await page.getByLabel('아이디',{exact:true}).fill('learner');await page.clock.fastForward(15000);await expect(active()).toContainText('다음 연습으로 이어가요');await expect(page.getByLabel('아이디',{exact:true})).toHaveValue('learner');
});
test('reduced motion starts stationary and keyboard scene selection remains available',async({page})=>{
 await page.clock.install();const preview=await open(page,'reduce');
 await page.clock.fastForward(15000);await expect(preview.locator('[data-active="true"]')).toContainText('내 방식으로 풀고');
 const next=preview.getByRole('button',{name:'2번째 예시 · 풀이 복습'});await next.focus();await page.keyboard.press('Enter');
 await expect(next).toHaveAttribute('aria-pressed','true');await expect(preview.getByRole('group',{name:'2 / 3 · 풀이 복습'})).toBeVisible();
 await expect(preview.getByRole('group',{name:'1 / 3 · 문제 풀이'})).toHaveCount(0);
 await page.clock.fastForward(15000);await expect(next).toHaveAttribute('aria-pressed','true');
 await preview.getByRole('button',{name:'예시 자동 재생',exact:true}).click();await page.mouse.move(0,0);await page.clock.fastForward(7100);await expect(preview.locator('[data-active="true"]')).toContainText('다음 연습으로 이어가요');
 await page.setViewportSize({width:390,height:844});await expect(preview).not.toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
