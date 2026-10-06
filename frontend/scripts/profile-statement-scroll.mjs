// Compare Chrome's scrolling layers and paint cost on a long problem statement.
import {chromium} from 'playwright';
const browser=await chromium.launch({args:['--disable-frame-rate-limit','--disable-background-timer-throttling']});
try {
 const page=await browser.newPage({viewport:{width:1440,height:950},deviceScaleFactor:2});
 await page.route('**/api/**',r=>{const path=new URL(r.request().url()).pathname;return r.fulfill({json:path==='/api/me'?{id:'scroll',nickname:'성능 확인'}:path==='/api/problems'?[{version:'scroll',title:'본문 스크롤',statement:Array.from({length:160},(_,i)=>`### 조건 ${i}\n\n입력 조건을 확인하고 결과를 계산합니다.\n\n|입력|출력|\n|---|---|\n|${i}|${i+1}|\n`).join('\n'),submissionsEnabled:true}]:[]});});
 await page.goto((process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790')+'/#practice');await page.getByLabel('Main.java',{exact:true}).waitFor();await page.evaluate(()=>document.fonts.ready);
 const client=await page.context().newCDPSession(page);let layers=[];client.on('LayerTree.layerTreeDidChange',e=>layers=e.layers||[]);await client.send('LayerTree.enable');
 const results=[];
 for(const [variant,css] of [['current','.problem-card{contain:none;transform:none}'],['contain','.problem-card{contain:layout paint;transform:none}'],['layer','.problem-card{contain:none;transform:translateZ(0)}'],['isolated-layer','.problem-card{contain:layout paint;transform:translateZ(0)}'],['current-repeat','.problem-card{contain:none;transform:none}']]) {
 const style=await page.addStyleTag({content:css});await page.locator('.problem-card').evaluate(n=>n.scrollTop=0);await page.waitForTimeout(300);
 await page.locator('.problem-card').hover();
 const events=[];const collect=e=>events.push(...e.value);client.on('Tracing.dataCollected',collect);await client.send('Tracing.start',{categories:'devtools.timeline,cc,gpu',transferMode:'ReportEvents'});
 await page.evaluate(()=>new Promise(resolve=>{const n=document.querySelector('.problem-card'),max=n.scrollHeight-n.clientHeight;let f=0;function tick(){n.scrollTop=max*(.5+.5*Math.sin(f/12));if(++f<120)requestAnimationFrame(tick);else resolve()}requestAnimationFrame(tick)}));
 const done=new Promise(r=>client.once('Tracing.tracingComplete',r));await client.send('Tracing.end');await done;client.off('Tracing.dataCollected',collect);
 const duration=name=>Math.round(events.filter(e=>e.ph==='X'&&e.name===name).reduce((s,e)=>s+(e.dur||0)/1000,0));
 const doc=await client.send('DOM.getDocument');const node=await client.send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'.problem-card'});const detail=await client.send('DOM.describeNode',{nodeId:node.nodeId});
 const own=layers.filter(l=>l.backendNodeId===detail.node.backendNodeId);const reasons=[];for(const l of own)try{reasons.push(await client.send('LayerTree.compositingReasons',{layerId:l.layerId}))}catch{}
 results.push({variant,rasterMs:duration('RasterTask'),paintMs:duration('Paint'),layoutMs:duration('Layout'),layers:own.map(l=>({width:l.width,height:l.height,scrollReasons:l.scrollRects})),reasons});
 await page.screenshot({path:`/tmp/gamja-statement-${variant}.png`});await style.evaluate(n=>n.remove());
 }console.log(JSON.stringify(results,null,2));
}finally{await browser.close()}
