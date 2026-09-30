import {test,expect} from '@playwright/test';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const exec=promisify(execFile);
const container=process.env.GAMJAOJ_COMPLETION_TEST_CONTAINER;
test.skip(!container,'Set GAMJAOJ_COMPLETION_TEST_CONTAINER to an isolated completion service.');
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const languages=[{id:'JAVA',label:'Java 8'},{id:'CPP',label:'C++17'},{id:'PYTHON',label:'Python 3.12'}];
for(const [language,source,want] of [
 ['JAVA','class Node { public String customValue(){return "x";} } class Main { void run(){Node n=new Node(); n.cus|} }','customValue'],
 ['CPP','#include <string>\nstruct Node { std::string custom_value(); };\nint main(){Node *n; n->cus|','custom_value'],
 ['PYTHON','class Node:\n    def custom_value(self) -> str: return "x"\nn=Node()\nn.cus|','custom_value'],
])test(`real ${language} member analysis reaches editor and Tab inserts call`,async({page})=>{
 test.setTimeout(60000);let analysis=0;
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;let data=[];
  if(path==='/api/me')data={id:'semantic-live',nickname:'완성'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'semantic',title:'완성',statement:'검증',sampleInput:'',sampleOutput:'',submissionsEnabled:true,languages}];
  if(path==='/api/editor/completions'){
   const body=JSON.stringify({...req.postDataJSON(),owner:'browser-smoke'});
   const result=await exec('docker',['exec',container,'python','-c','import urllib.request,sys; r=urllib.request.Request("http://localhost:8090/complete",data=sys.argv[1].encode(),headers={"Content-Type":"application/json"}); print(urllib.request.urlopen(r,timeout=35).read().decode())',body],{timeout:40000,maxBuffer:1024*1024});
   data=JSON.parse(result.stdout);analysis++;
  }
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
 const editor=page.getByLabel(language==='JAVA'?'Main.java':language==='CPP'?'Main.cpp':'Main.py',{exact:true});
 await editor.fill(source.replace('|',''));await editor.press('Control+End');
 for(let i=0;i<source.length-source.indexOf('|')-1;i++)await editor.press('ArrowLeft');
 await editor.press('Control+Space');await expect.poll(()=>analysis,{timeout:35000}).toBeGreaterThan(0);
 const selected=page.locator('.cm-tooltip-autocomplete [aria-selected="true"]');await expect(selected).toContainText(want);await expect(selected).toContainText(language==='JAVA'?'Node.customValue':language==='CPP'?'std::string':'custom_value');
 await page.waitForTimeout(100);await editor.press('Tab');await expect(editor).toContainText(want+'()');
});
