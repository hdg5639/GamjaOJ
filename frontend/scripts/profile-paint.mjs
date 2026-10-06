// Compare the old and cached paint paths using the same browser, scene and input.
// Run: GAMJAOJ_BASE_URL=http://127.0.0.1:18790 node scripts/profile-paint.mjs
import {chromium} from 'playwright';
const browser=await chromium.launch({args:['--disable-frame-rate-limit','--disable-background-timer-throttling']});
try {
 const page=await browser.newPage({viewport:{width:1440,height:950}});
 await page.route('**/api/**',route=>{
  const path=new URL(route.request().url()).pathname;
  return route.fulfill({json:path==='/api/me'?{id:'paint-profile',nickname:'성능 확인'}:path==='/api/problems'?Array.from({length:15},(_,i)=>({version:'paint-'+i,title:'스크롤·호버 확인 '+i,statement:'본문',shared:true,submissionsEnabled:true})):[]});
 });
 await page.goto(process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18790');
 await page.locator('.catalog-list').waitFor();await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(600);
 const client=await page.context().newCDPSession(page);
 const results=[];
 for(const variant of ['previous','cached']) {
  const override=variant==='previous'?await page.addStyleTag({content:':root body{background-attachment:fixed}:root body::before{content:none}button{transform:none}'}):null;
  for(const interaction of ['scroll','hover']) {
   await page.mouse.move(0,0);await page.locator('.catalog-view').evaluate(n=>n.scrollTop=0);await page.waitForTimeout(200);
   const events=[];const collect=event=>events.push(...event.value);client.on('Tracing.dataCollected',collect);
   await client.send('Tracing.start',{categories:'devtools.timeline,cc,gpu',transferMode:'ReportEvents'});
   if(interaction==='scroll') {
    await page.evaluate(()=>new Promise(resolve=>{let frame=0;const n=document.querySelector('.catalog-view'),max=n.scrollHeight-n.clientHeight;function tick(){n.scrollTop=max*(.5+.5*Math.sin(frame/8));if(++frame<60)requestAnimationFrame(tick);else resolve();}requestAnimationFrame(tick);}));
   } else {
    const points=await page.locator('.workspace-nav button,.catalog-play,.account-nav button').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};}).filter(p=>p.y>0&&p.y<900));
    for(let i=0;i<100;i++){const p=points[i%points.length];await client.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:p.x,y:p.y});await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));}
   }
   const done=new Promise(resolve=>client.once('Tracing.tracingComplete',resolve));await client.send('Tracing.end');await done;client.off('Tracing.dataCollected',collect);
   const duration=name=>Math.round(events.filter(e=>e.ph==='X'&&e.name===name).reduce((total,e)=>total+(e.dur||0)/1000,0)*100)/100;
   results.push({variant,interaction,rasterMs:duration('RasterTask'),layoutMs:duration('Layout'),paintMs:duration('Paint')});
  }
  if(override)await override.evaluate(n=>n.remove());
 }
 console.log(JSON.stringify(results,null,2));
} finally {await browser.close();}
