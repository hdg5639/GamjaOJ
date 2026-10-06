// Chrome paint comparison at DPR 2, using identical statements and editor content.
// Run from frontend: GAMJAOJ_BASE_URL=http://127.0.0.1:18790 node scripts/profile-solving-paint.mjs
import {chromium} from 'playwright';
import {readFile} from 'node:fs/promises';
const browser=await chromium.launch({args:['--disable-frame-rate-limit','--disable-background-timer-throttling']});
try {
 const results=[];
 for(const variant of ['previous','lightweight']) {
 const page=await browser.newPage({viewport:{width:1440,height:950},deviceScaleFactor:2});
 const question={version:'paint',title:'긴 문제',statement:Array.from({length:120},(_,i)=>`### 조건 ${i}\n\n입력 조건을 확인하고 결과를 계산합니다.\n`).join('\n'),examples:[{input:'1 2',output:'3'}],submissionsEnabled:true};
 await page.route('**/api/**',route=>{const path=new URL(route.request().url()).pathname;return route.fulfill({json:path==='/api/me'?{id:'paint',nickname:'성능 확인'}:path==='/api/problems'?[question]:[]});});
 await page.goto((process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790')+'/#practice');
 await page.getByLabel('Main.java',{exact:true}).fill(Array.from({length:300},(_,i)=>`// line ${i}`).join('\n'));
 if(variant==='previous')await page.addStyleTag({content:(process.env.GAMJAOJ_PREVIOUS_CSS?await readFile(process.env.GAMJAOJ_PREVIOUS_CSS,'utf8'):`:root{--surface-raised:4px 4px 10px var(--relief-shade),-4px -4px 10px var(--relief-light);--surface-inset:inset 2px 2px 5px var(--relief-shade),inset -2px -2px 5px var(--relief-light)}:root:root:root .practice-grid{backdrop-filter:blur(22px);box-shadow:inset 1px 1px 0 var(--relief-light),6px 6px 20px var(--relief-shade),-4px -4px 14px var(--relief-light)}:root:root:root .header-slot{backdrop-filter:blur(24px)}:root:root:root .app-navigation{backdrop-filter:blur(28px)}:root:root:root .workspace-nav button{box-shadow:3px 3px 7px #00000015,-3px -3px 7px #ffffff03}:root:root:root .workspace-nav button.active{box-shadow:inset 2px 2px 5px #00000018,inset -2px -2px 5px #ffffff0d}`)+'button{transform:translateZ(0)}button:not(:disabled):hover{filter:brightness(.96)}'});
 else {for(const file of ['surface-theme.css'])await page.addStyleTag({content:await readFile(new URL('../app/'+file,import.meta.url),'utf8')});await page.addStyleTag({content:'button{transform:translateZ(0)}button:not(:disabled):hover{filter:none}.primary:hover:not(:disabled),.primary:active:not(:disabled){transform:none}'});}
 await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(700);await page.screenshot({path:`/tmp/gamja-material-${variant}.png`});
 const client=await page.context().newCDPSession(page);
 for(const interaction of ['problem-scroll','editor-scroll','hover']) {
 const events=[];const collect=e=>events.push(...e.value);client.on('Tracing.dataCollected',collect);
 await client.send('Tracing.start',{categories:'devtools.timeline,cc,gpu',transferMode:'ReportEvents'});
 if(interaction==='hover') {
 const points=await page.locator('.workspace-nav button,.practice-view button').evaluateAll(ns=>ns.map(n=>{const r=n.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}}).filter(p=>p.x>0&&p.y>0&&p.y<900));
 for(let i=0;i<80;i++){await client.send('Input.dispatchMouseEvent',{type:'mouseMoved',...points[i%points.length]});await page.evaluate(()=>new Promise(r=>requestAnimationFrame(r)));}
 } else await page.evaluate(selector=>new Promise(resolve=>{const n=document.querySelector(selector),max=n.scrollHeight-n.clientHeight;let f=0;function tick(){n.scrollTop=max*(.5+.5*Math.sin(f/8));if(++f<60)requestAnimationFrame(tick);else resolve()}requestAnimationFrame(tick)}),interaction==='problem-scroll'?'.problem-card':'.editor-view:not([hidden]) .cm-scroller');
 const done=new Promise(r=>client.once('Tracing.tracingComplete',r));await client.send('Tracing.end');await done;client.off('Tracing.dataCollected',collect);
 const duration=name=>Math.round(events.filter(e=>e.ph==='X'&&e.name===name).reduce((s,e)=>s+(e.dur||0)/1000,0));
 results.push({variant,interaction,rasterMs:duration('RasterTask'),layoutMs:duration('Layout'),paintMs:duration('Paint')});
 }await page.close();
 }console.log(JSON.stringify(results,null,2));
}finally{await browser.close()}
