import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
const manifest=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/problem-illustrations-v1.json',import.meta.url)));
const figure=manifest[0],src='/problem-illustrations/'+figure.file;
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
async function setup(page,{width=1440,diagnostic=false,owned=false,body,failFirst=false}={}){
 await page.setViewportSize({width,height:900});await page.emulateMedia({reducedMotion:'reduce'});await page.addInitScript(theme=>localStorage.setItem('gamjaoj-theme',theme),width===390?'dark':'light');
 const p={version:'drawing',problemVersion:'drawing',itemId:'item',title:'그림이 있는 문제',statement:body||'## 이동 규칙\n\n**벽**을 넘을 수 없어요.\n다음 줄도 유지해요.\n\n| 기호 | 의미 |\n| --- | --- |\n| # | 벽 |\n\n<script>window.imageScript=true</script>\n\n[위험](javascript:alert(1))\n\n![외부 그림](https://example.invalid/tracking.png)',examples:[{input:'1 2',output:'3'}],submissionsEnabled:true};
 const session={id:'session',status:'ACTIVE',items:[{id:'item',position:0,category:'implementation',difficulty:'EASY',status:'OPEN',attempts:0,pending:0}],current:p};
 let images=[{...figure,id:null,src}],keys=[],uploads=0,readFails=failFirst?1:0;
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'drawing-user',nickname:'테스트'};
  if(path==='/api/problems')data=[p];
  if(path==='/api/diagnostics')data=[session];if(path==='/api/diagnostics/session')data=session;
  if(path.startsWith('/api/problem-images/'))return route.fulfill({contentType:'image/png',body:png});
  if(path==='/api/problems/drawing/illustrations'){
   if(req.method()==='POST'){
    const id=req.headers()['idempotency-key'];keys.push(id);uploads++;
    expect(req.headers()['content-type']).toContain('multipart/form-data; boundary=');expect(req.postDataBuffer().toString()).toContain('그림의 이동 방향');
    if(!images.some(i=>i.id===id))images.push({id,src:'/api/problem-images/'+id,alt:'그림의 이동 방향',caption:'오른쪽으로 이동',width:1,height:1,kind:'AUTHOR'});
    if(uploads===1){readFails=1;return route.abort();}
   }else if(readFails){readFails--;return route.fulfill({status:503,json:{message:'다시 확인해 주세요.'}});}
   data={canEdit:owned,illustrations:images};
  }
  if(req.method()==='DELETE'&&path.startsWith('/api/problems/drawing/illustrations/')){images=images.filter(i=>i.id!==path.split('/').at(-1));data={canEdit:owned,illustrations:images};}
  await route.fulfill({json:data});
 });
 await page.goto(base+(diagnostic?'/#diagnostic':'/#practice'));
 if(width===390)await page.getByRole('button',{name:'문제 보기',exact:true}).click();
 return {keys,uploads:()=>uploads};
}
for(const width of [390,1440])test(`safe Markdown and structure image with keyboard zoom ${width}`,async({page})=>{
 let external=0;page.on('request',req=>{if(req.url().includes('example.invalid'))external++;});await setup(page,{width});
 const statement=page.locator('.problem-card .problem-statement');await expect(statement.getByRole('heading',{name:'이동 규칙'})).toBeVisible();await expect(statement.locator('strong')).toHaveText('벽');await expect(statement.locator('table')).toContainText('의미');await expect(statement).toContainText('등록된 이미지 주소가 필요해요.');
 const button=statement.getByRole('button',{name:figure.alt+' 확대'});await expect(button.locator('img')).toHaveJSProperty('naturalWidth',800);
 expect(await page.evaluate(()=>window.imageScript)).toBeUndefined();expect(external).toBe(0);await expect(statement.getByRole('link',{name:'위험'})).toHaveAttribute('href','');
 await button.focus();await button.press('Enter');const modal=page.getByRole('dialog',{name:figure.alt,exact:true});await expect(modal).toBeVisible();await expect(modal.locator('img')).toHaveJSProperty('naturalWidth',800);await page.screenshot({path:`/tmp/gamja-problem-image-${width}.png`,animations:'disabled'});await page.keyboard.press('Escape');await expect(modal).not.toBeVisible();await expect(button).toBeFocused();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});
test('owner multipart upload keeps retry key after lost response and delete restores list',async({page})=>{
 const state=await setup(page,{owned:true});await page.getByRole('button',{name:'문제 그림 관리'}).click();const modal=page.getByRole('dialog',{name:'문제 그림 관리',exact:true});await modal.locator('input[type=file]').setInputFiles({name:'diagram.png',mimeType:'image/png',buffer:png});await modal.getByLabel('그림 설명',{exact:true}).fill('그림의 이동 방향');await modal.getByLabel('캡션',{exact:true}).fill('오른쪽으로 이동');await modal.getByRole('button',{name:'그림 추가',exact:true}).click();await expect(modal.getByRole('button',{name:'같은 업로드 요청 다시 확인'})).toBeEnabled();await modal.getByRole('button',{name:'같은 업로드 요청 다시 확인'}).click();await expect(modal.locator('.statement-upload-list li')).toHaveCount(2);expect(state.keys).toHaveLength(2);expect(state.keys[0]).toBe(state.keys[1]);await modal.getByRole('button',{name:'삭제',exact:true}).click();await modal.getByRole('button',{name:'이 그림 삭제'}).click();await expect(modal.locator('.statement-upload-list li')).toHaveCount(1);
});
test('diagnostic uses same renderer without image management',async({page})=>{
 await setup(page,{diagnostic:true,owned:true});const statement=page.locator('.diagnostic-workspace .problem-statement');await expect(statement.getByRole('heading',{name:'이동 규칙'})).toBeVisible();await expect(statement.locator('.statement-image-button img')).toHaveJSProperty('naturalWidth',800);await expect(statement.getByRole('button',{name:'문제 그림 관리'})).toHaveCount(0);
});
test('illustration metadata load failure has local retry',async({page})=>{
 await setup(page,{failFirst:true});const statement=page.locator('.problem-card .problem-statement');await expect(statement.getByRole('status')).toContainText('문제 그림을 불러오지 못했어요.');await statement.getByRole('button',{name:'다시 불러오기'}).click();await expect(statement.locator('.statement-image-button img')).toHaveJSProperty('naturalWidth',800);
});
test('failed image retries and clipboard image is previewed without uploading',async({page})=>{
 let failed=true;await page.route('**'+src,route=>{if(failed){failed=false;return route.fulfill({status:503,body:'unavailable'});}return route.continue();});await setup(page,{owned:true});const statement=page.locator('.problem-card .problem-statement');await expect(statement.getByRole('status')).toContainText('그림을 불러오지 못했어요.');await statement.getByRole('button',{name:'다시 불러오기'}).click();await expect(statement.locator('.statement-image-button img')).toHaveJSProperty('naturalWidth',800);
 await statement.getByRole('button',{name:'문제 그림 관리'}).click();const modal=page.getByRole('dialog',{name:'문제 그림 관리',exact:true});await modal.getByLabel('그림 붙여넣기 영역').evaluate((el,data)=>{const bytes=Uint8Array.from(atob(data),c=>c.charCodeAt(0)),transfer=new DataTransfer();transfer.items.add(new File([bytes],'clipboard.png',{type:'image/png'}));el.dispatchEvent(new ClipboardEvent('paste',{clipboardData:transfer,bubbles:true,cancelable:true}));},png.toString('base64'));await expect(modal.getByAltText('추가할 그림 미리보기')).toHaveAttribute('src',/^blob:/);await expect(modal.getByRole('button',{name:'그림 추가',exact:true})).toBeDisabled();
});
