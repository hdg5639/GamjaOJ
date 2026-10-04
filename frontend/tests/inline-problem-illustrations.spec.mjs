import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18789';
const manifest=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/problem-illustrations-v1.json',import.meta.url))),f=manifest.find(x=>x.version==='iamywl-v1-241bfb705697b488');
for(const width of [390,1440])test(`narrative graph between rule paragraph and continuing explanation ${width}`,async({page})=>{
 await page.setViewportSize({width,height:1000});await page.emulateMedia({reducedMotion:'reduce'});await page.addInitScript(t=>localStorage.setItem('gamjaoj-theme',t),width===390?'dark':'light');
 const problem={version:f.version,title:'작업 순서 결정',statement:'## 작업의 선후 관계\n\n먼저 끝나야 하는 작업이 있습니다.\n\n'+f.afterParagraph+'\n\n## 입력\n\n작업 수와 선후 관계를 입력합니다.',examples:[{input:'1',output:'1'}],submissionsEnabled:true};
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];if(path==='/api/me')data={id:'inline-user',nickname:'테스트'};if(path==='/api/problems')data=[problem];if(path.endsWith('/illustrations'))data={canEdit:false,illustrations:[{...f,id:null,src:'/problem-illustrations/'+f.file}]};await route.fulfill({json:data});});
 await page.goto(base+'/#practice');if(width===390)await page.getByRole('button',{name:'문제 보기',exact:true}).click();
 const statement=page.locator('.problem-card .problem-statement'),imageParagraph=statement.locator('p[data-inline-illustration]');await expect(imageParagraph).toHaveCount(1);await expect(imageParagraph.locator('img')).toHaveJSProperty('naturalWidth',800);await expect(statement.locator('.statement-illustrations')).toHaveCount(0);await expect(statement.locator('.statement-figure-explanation')).toHaveText(f.explanation);
 const order=await statement.evaluate(el=>Array.from(el.children).map(e=>e.hasAttribute('data-inline-illustration')?'image':e.classList.contains('statement-figure-explanation')?'explanation':e.textContent));const i=order.indexOf('image');expect(order[i-1]).toBe(f.afterParagraph);expect(order[i+1]).toBe('explanation');expect(order[i+2]).toBe('입력');
 await imageParagraph.scrollIntoViewIfNeeded();await page.screenshot({path:`/tmp/gamja-inline-body-${width}.png`,animations:'disabled'});await imageParagraph.getByRole('button',{name:f.alt+' 확대'}).click();await expect(page.getByRole('dialog',{name:f.alt,exact:true})).toBeVisible();await page.keyboard.press('Escape');await expect(imageParagraph.getByRole('button')).toBeFocused();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
 await page.reload();if(width===390)await page.getByRole('button',{name:'문제 보기',exact:true}).click();await expect(imageParagraph).toHaveCount(1);
});
