import {test,expect} from '@playwright/test';
import {expectCode} from './editor-helpers.mjs';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
async function setup(page,language='JAVA'){
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'snippets',nickname:'축약어'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/editor/completions')data={items:[]};
  if(path==='/api/problems')data=[{version:'v1',title:'축약어',statement:'검증',sampleInput:'',sampleOutput:'',submissionsEnabled:true,languages:[{id:'JAVA',label:'Java 8'},{id:'CPP',label:'C++17'},{id:'PYTHON',label:'Python 3.12'}]}];
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
 return page.getByLabel(language==='JAVA'?'Main.java':language==='CPP'?'Main.cpp':'Main.py',{exact:true});
}
async function expand(page,editor,alias,key='Tab',prefix=''){
 await editor.fill(prefix);await editor.press('Control+End');await editor.pressSequentially(alias);
 const selected=page.locator('.cm-tooltip-autocomplete [aria-selected="true"]');
 await expect(selected).toContainText(alias);await expect(selected).toContainText('코드 템플릿');await page.waitForTimeout(100);await editor.press(key);
}
for(const alias of ['sysout','sout'])test(`Java ${alias} expands and positions cursor`,async({page})=>{
 const e=await setup(page);await expand(page,e,alias,'Tab','class Main {\n    void run() {\n        ');
 await e.pressSequentially('"hello"');await e.press('Tab');await e.pressSequentially('// done');
 await expectCode(e,'class Main {\n    void run() {\n        System.out.println("hello");// done');
});
for(const language of ['JAVA','CPP','PYTHON'])test(`${language} main expands with Enter and correct indentation`,async({page})=>{
 const e=await setup(page,language);await expand(page,e,'main','Enter');
 const expected=language==='JAVA'?'public static void main(String[] args) {\n    \n}':language==='CPP'?'int main() {\n    \n    return 0;\n}':'def main():\n    pass\n\n\nif __name__ == "__main__":\n    main()';
 await expectCode(e,expected);await e.pressSequentially(language==='PYTHON'?'print(1)':'// body');await expect(e).toContainText(language==='PYTHON'?'    print(1)':'    // body');
});
for(const language of ['JAVA','CPP'])test(`${language} linked loop index and field navigation`,async({page})=>{
 const e=await setup(page,language);await expand(page,e,'fori');await e.pressSequentially('idx');await e.press('Tab');await e.pressSequentially('count');await e.press('Shift+Tab');await e.pressSequentially('j');await e.press('Tab');await e.press('Tab');await e.pressSequentially('// body');
 await expectCode(e,`for (int j = 0; j < count; ${language==='CPP'?'++j':'j++'}) {\n    // body\n}`);
});
test('Python input and functions and C++ linked sort',async({page})=>{
 let e=await setup(page,'PYTHON');await expand(page,e,'readints');await e.pressSequentially('numbers');await e.press('Tab');await expectCode(e,'numbers = list(map(int, input().split()))');
 await expand(page,e,'defn');await e.pressSequentially('solve');await e.press('Tab');await e.pressSequentially('n');await e.press('Tab');await e.pressSequentially('return n');await expectCode(e,'def solve(n):\n    return n');
 await page.getByLabel('풀이 언어',{exact:true}).selectOption('CPP');e=page.getByLabel('Main.cpp',{exact:true});await expand(page,e,'sortv');await e.pressSequentially('items');await expectCode(e,'std::sort(items.begin(), items.end());');
});
test('no templates in comments strings or member access, Escape retains indentation',async({page})=>{
 const e=await setup(page);
 for(const code of ['// sysout','/*\n sysout','String text = "sysout','node.sysout']){
  await e.fill(code);await e.press('Control+End');await e.press('Control+Space');await page.waitForTimeout(200);await expect(page.getByRole('option').filter({hasText:'코드 템플릿'})).toHaveCount(0);
 }
 await expand(page,e,'fori');await e.press('Escape');await e.fill('');await e.press('Tab');await expectCode(e,'    ');
});
test('Vim insert mode accepts a template and Escape restores normal mode',async({page})=>{
 const e=await setup(page);await e.fill('');
 await page.evaluate(()=>{localStorage.setItem('gamjaoj-editor-vim','on');window.dispatchEvent(new Event('gamjaoj-editor-settings'));});
 await e.click();await e.press('i');await e.pressSequentially('sout');
 await expect(page.locator('.cm-tooltip-autocomplete [aria-selected="true"]')).toContainText('코드 템플릿');await page.waitForTimeout(100);await e.press('Tab');await e.pressSequentially('42');await expectCode(e,'System.out.println(42);');
 await e.press('Escape');await e.press('0');await e.press('x');await expectCode(e,'ystem.out.println(42);');
});
