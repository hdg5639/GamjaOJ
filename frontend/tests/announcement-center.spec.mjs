import {test,expect} from '@playwright/test';
import {announcements} from './fixtures/announcements.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
async function open(page,{signedIn=false,width=1440,dark=false}={}){
 page.on('pageerror',e=>console.log('Browser error:',e.message));
 await page.setViewportSize({width,height:950});await page.emulateMedia({reducedMotion:'reduce',colorScheme:dark?'dark':'light'});
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  return route.fulfill(path==='/api/me'&&!signedIn?{status:401,json:{}}:{json:path==='/api/announcements'?announcements:path==='/api/me'?{id:'notice-user',nickname:'테스트'}:[]});
 });
 await page.goto(base);await expect(page.getByRole('button',{name:/공지·업데이트/})).toHaveAttribute('aria-label','공지·업데이트 · 읽지 않은 글 4개');
 return page.getByRole('dialog',{name:'공지·업데이트',exact:true});
}
test('opening preserves unread; reading individual entries persists and filters do not mark other entries',async({page})=>{
 const dialog=await open(page),trigger=page.getByRole('button',{name:/공지·업데이트/});await trigger.click();await expect(dialog.getByRole('status')).toContainText('4개');
 await dialog.getByRole('button',{name:'새 기능',exact:true}).click();await expect(dialog.locator('.announcement-list > li')).toHaveCount(1);
 const article=dialog.getByRole('button',{name:/여러 입력을 한 번에 실행해요/});await article.click();await expect(article).toHaveAttribute('aria-expanded','true');await expect(dialog.getByText('읽지 않은 소식 3개')).toBeVisible();
 await dialog.press('Escape');await expect(trigger).toBeFocused();await page.reload();await expect(trigger).toHaveAttribute('aria-label','공지·업데이트 · 읽지 않은 글 3개',{timeout:15000});
 await trigger.click();await expect(dialog.getByRole('button',{name:/여러 입력을 한 번에 실행해요/})).not.toContainText('새 글');await dialog.getByRole('button',{name:'모두 읽음',exact:true}).click();await expect(dialog.getByRole('status')).toContainText('모두 확인');await expect(trigger).toHaveAttribute('aria-label','공지·업데이트');await dialog.getByRole('button',{name:'닫기',exact:true}).click();await expect(trigger).toBeFocused();
});
for(const width of [390,768,1440])for(const signedIn of [false,true])test('notice layout '+width+' '+(signedIn?'account':'anonymous'),async({page})=>{
 const dialog=await open(page,{width,signedIn,dark:signedIn});await page.getByRole('button',{name:/공지·업데이트/}).click();await expect(dialog).toBeVisible();
 await dialog.getByRole('button',{name:/제출·실행이 몰리면/}).click();await expect(dialog.getByText('대기 시간은 코드의 실행 시간 제한과 별개예요.',{exact:false})).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 const rect=await dialog.boundingBox();expect(rect.x).toBeGreaterThanOrEqual(0);expect(rect.x+rect.width).toBeLessThanOrEqual(width);
 await page.screenshot({path:'/tmp/gamja-notices-'+width+'-'+(signedIn?'dark':'light')+'.png',fullPage:true});
 await dialog.press('Escape');await expect(dialog).not.toBeVisible();
});
test('malformed read state and unavailable storage leave notices usable',async({page})=>{
 await page.addInitScript(()=>{localStorage.setItem('gamjaoj-notice-read-v1:anonymous','{"broken":true}');});
 const dialog=await open(page);await page.evaluate(()=>{Storage.prototype.setItem=()=>{throw new Error('blocked');};});await page.getByRole('button',{name:/공지·업데이트/}).click();await dialog.getByRole('button',{name:'모두 읽음',exact:true}).click();await expect(dialog.getByRole('status')).toContainText('모두 확인');await dialog.press('Escape');await expect(page.getByRole('button',{name:'공지·업데이트',exact:true})).toBeVisible();
});
test('account read state is separate from anonymous state and synchronizes another tab',async({page,context})=>{
 await page.addInitScript(()=>localStorage.setItem('gamjaoj-notice-read-v1:anonymous',JSON.stringify(['queue-guide-20261007','batch-run-20261007','cpu-time-20261007','editor-selection-20261007'])));
 const dialog=await open(page,{signedIn:true});await page.getByRole('button',{name:/공지·업데이트/}).click();const other=await context.newPage();await other.goto(base);await other.evaluate(()=>localStorage.setItem('gamjaoj-notice-read-v1:notice-user',JSON.stringify(['queue-guide-20261007','batch-run-20261007','cpu-time-20261007','editor-selection-20261007'])));await expect(dialog.getByRole('status')).toContainText('모두 확인');await other.close();
});
