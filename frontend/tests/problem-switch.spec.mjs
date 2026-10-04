import {test,expect} from '@playwright/test';
import {readFileSync} from 'node:fs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790';
const drawing=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/problem-illustrations-v1.json',import.meta.url)))[0];
for(const width of [390,1440])test(`problem content never mixes when switching with a late image response ${width}`,async({page})=>{
 await page.setViewportSize({width,height:950});let release,first=true,received=false;const gate=new Promise(r=>release=r);
 const problems=['alpha','beta'].map(v=>({version:v,title:v==='alpha'?'직전 문제':'새로 선택한 문제',statement:v==='alpha'?'직전 본문만 나타납니다.\n\n'+'이전 규칙입니다.\n'.repeat(50):'새 문제의 본문입니다.\n\n새 문제만의 규칙입니다.',examples:[{input:v,output:v}],submissionsEnabled:true}));
 await page.route('**/api/**',async route=>{const path=new URL(route.request().url()).pathname;let data=[];if(path==='/api/me')data={id:'switch-user',nickname:'전환'};if(path==='/api/problems')data=problems;
 if(path.endsWith('/illustrations')){const v=path.split('/')[3];if(v==='alpha'&&first){first=false;received=true;await gate;}data={canEdit:false,illustrations:[{...drawing,id:null,src:'/problem-illustrations/'+drawing.file,alt:v==='alpha'?'직전 그림':'새 그림'}]};}
 await route.fulfill({json:data});});
 await page.goto(base+'/?problem=alpha#practice');await expect(page.locator('#problem-title')).toHaveText('직전 문제');await expect.poll(()=>received).toBe(true);
 const choice=page.getByRole('combobox',{name:'풀이할 문제',exact:true}),card=page.locator('.problem-card');await choice.selectOption('beta');await expect(page.locator('#problem-title')).toHaveText('새로 선택한 문제');await expect(card).toContainText('새 문제의 본문입니다.');await expect(card).not.toContainText('직전 본문');release();await expect(card.getByAltText('새 그림')).toHaveJSProperty('naturalWidth',800);await expect(card.getByAltText('직전 그림')).toHaveCount(0);
 for(const v of ['alpha','beta','alpha','beta']){await choice.selectOption(v);await expect(card).toContainText(v==='alpha'?'직전 본문만':'새 문제의 본문');await expect(card).not.toContainText(v==='alpha'?'새 문제만의 규칙':'이전 규칙입니다.');await expect(card.locator('.examples')).toContainText(v);}
 await page.getByRole('button',{name:'문제 탐색',exact:true}).click();const catalog=page.getByRole('region',{name:'문제 목록',exact:true});await catalog.locator('.catalog-list>li').filter({hasText:'직전 문제'}).getByRole('button',{name:'직전 문제 · alpha 풀기',exact:true}).click();await expect(card).toContainText('직전 본문만');await expect(card).not.toContainText('새 문제만의 규칙');
});
