import {test,expect} from '@playwright/test';
const base=process.env.GAMJAOJ_BASE_URL||'http://127.0.0.1:18788';
const cases=[
 {language:'JAVA',prefix:'Buff',want:'BufferedReader',rare:'BufferPoolFactory',doc:'class Main { void run() { ',local:'BufferMine'},
 {language:'CPP',prefix:'vect',want:'vector',rare:'vector_internal_base',doc:'int main() { ',local:'vectorMine'},
 {language:'PYTHON',prefix:'defa',want:'defaultdict',rare:'default_internal_factory',doc:'',local:'defaultMine'},
];
async function setup(page,language,items){
 let release;const gate=new Promise(resolve=>release=resolve);
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname;let data=[];
  if(path==='/api/me')data={id:'completion-stability',nickname:'완성'};
  if(path==='/api/auth/csrf')data={headerName:'X-CSRF',token:'test'};
  if(path==='/api/problems')data=[{version:'stable',title:'완성 검증',statement:'설명',submissionsEnabled:true,languages:cases.map(c=>({id:c.language,label:c.language}))}];
  if(path==='/api/editor/completions'){await gate;data={items};}
  await route.fulfill({json:data});
 });
 await page.goto(base+'/#practice');await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
 const editor=page.locator('#source .cm-content');
 return {editor,release,selected:page.locator('.cm-tooltip-autocomplete [aria-selected="true"]')};
}
for(const c of cases){
 test(`${c.language}: common candidates stay ahead of delayed unfamiliar results`,async({page})=>{
  const {editor,release,selected}=await setup(page,c.language,[{label:c.rare,kind:7,detail:'library'}]);
  await editor.fill(c.doc+c.prefix);await editor.press('Control+End');await editor.press('Control+Space');
  await expect(selected).toContainText(c.want);const before=await selected.textContent();
  release();await expect(page.getByRole('option',{name:new RegExp(c.rare)})).toBeVisible();
  await expect(selected).toHaveText(before);await editor.press('Tab');await expect(editor).toContainText(c.want);
 });
 test(`${c.language}: document names and keyboard selection survive late results`,async({page})=>{
  const {editor,release,selected}=await setup(page,c.language,[{label:c.rare,kind:7,detail:'library'}]);
  const declaration=c.language==='JAVA'?`int ${c.local}; `:c.language==='CPP'?`int ${c.local}; `:`${c.local} = 0\n`;
  await editor.fill(c.doc+declaration+c.prefix);await editor.press('Control+End');await editor.press('Control+Space');
  await expect(selected).toContainText(c.local);await editor.press('ArrowDown');const chosen=await selected.textContent();
  release();await expect(page.getByRole('option',{name:new RegExp(c.rare)})).toBeVisible();await expect(selected).toHaveText(chosen);
 });
 test(`${c.language}: semantic-only members remain available and insert calls`,async({page})=>{
  const {editor,release,selected}=await setup(page,c.language,[{label:'novelMethod()',filterText:'novelMethod',insertText:'novelMethod',kind:2,detail:'Resolved member'}]);
  await editor.fill(c.doc+'node.nove');await editor.press('Control+End');await editor.press('Control+Space');
  release();await expect(selected).toContainText('novelMethod');await editor.press('Tab');await expect(editor).toContainText('node.novelMethod()');
 });
}
test('late results cannot edit a replacement draft',async({page})=>{
 const {editor,release}=await setup(page,'JAVA',[{label:'BufferedLegacy',kind:7,detail:'old'}]);
 await editor.fill('class Main { Buff');await editor.press('Control+End');await editor.press('Control+Space');
 await expect(page.locator('.cm-tooltip-autocomplete')).toBeVisible();await editor.fill('// replacement draft');release();
 await expect(page.getByRole('option',{name:/BufferedLegacy/})).toHaveCount(0);await editor.press('Tab');await expect(editor).not.toContainText('BufferedLegacy');
});
