import { expectCode } from './editor-helpers.mjs';
import { test, expect } from '@playwright/test';

// Exercise browser storage independently of authentication; auth.spec covers real accounts and API.
const base = process.env.GAMJAOJ_BASE_URL || 'http://127.0.0.1:18781';
async function workspace(page) {
  let userId = 'draft-user-a';
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const data = path === '/api/me'
      ? { id: userId, username: userId, nickname: userId, trainingGoal: '' }
      : path === '/api/problems' ? ['v1', 'v2'].map(version => ({ version, title: '초안 테스트',
        statement: '테스트 문제', sampleInput: '1 2', sampleOutput: '3', submissionsEnabled: true })) : [];
    await route.fulfill({ json: data });
  });
  await page.goto(base+'/#practice');
  await expect(page.getByLabel('Main.java', { exact: true })).toBeVisible();
  return async id => {
    userId = id;
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(page.locator('.user-name')).toHaveText(`${id}님`);
  };
}

test('drafts survive immediate reload, empty edits, problem changes and account switches', async ({ page }) => {
  const switchUser = await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await editor.fill('// 첫 번째 문제의 초안');
  await page.reload();
  await expectCode(editor, '// 첫 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v2');
  await expectCode(editor, '// 첫 번째 문제의 초안', true);
  await editor.fill('// 두 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v1');
  await expectCode(editor, '// 첫 번째 문제의 초안');
  await switchUser('draft-user-b');
  await expectCode(editor, '// 첫 번째 문제의 초안', true);
  await editor.fill('// 다른 계정');
  await switchUser('draft-user-a');
  await expectCode(editor, '// 첫 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v2');
  await expectCode(editor, '// 두 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v1');
  await editor.fill('');
  await page.reload();
  await expectCode(editor, '');
});

test('invalid file imports preserve the draft; valid UTF-8 source downloads unchanged', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await page.locator('#editor-tools > summary').click();
  const file = page.getByLabel('Java 파일 불러오기');
  const source = '// 한글 코드\npublic class Main {}\n';
  await file.setInputFiles({ name: 'Solution.java', mimeType: 'text/plain', buffer: Buffer.from(source) });
  await expectCode(editor, source);
  for (const [name, buffer] of [['Main.txt', Buffer.from('wrong extension')],
    ['Main.java', Buffer.alloc(65537, 65)], ['Main.java', Buffer.from([0xff, 0xfe, 0x80])]]) {
    await file.setInputFiles({ name, mimeType: 'text/plain', buffer });
    await expect(page.getByRole('region', { name: '문제 풀이' }).getByRole('alert')).toBeVisible();
    await expectCode(editor, source);
  }
  const downloaded = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Main.java 내려받기' }).click();
  const download = await downloaded;
  expect(download.suggestedFilename()).toBe('Main.java');
  const chunks = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk);
  expect(Buffer.concat(chunks).toString('utf8')).toBe(source);
});

test('unavailable storage keeps editor content and reports that saving failed', async ({ page }) => {
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (this === localStorage) throw new DOMException('Storage blocked', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  await workspace(page);
  await page.getByLabel('Main.java', { exact: true }).fill('// 저장 실패에도 편집 유지');
  await expect(page.getByText('자동 저장하지 못했어요.', { exact: false })).toBeVisible();
  await expectCode(page.getByLabel('Main.java', { exact: true }), '// 저장 실패에도 편집 유지');
});

test('workspace opens on the editor, initializes sample input and preserves edits across tool views', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await expect(editor).toBeInViewport();
  await expect(page.getByLabel('추가 1 · 입력', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '테스트 케이스 추가', exact: true }).click();
  await page.getByRole('button', { name: '예제 1 입력으로 추가', exact: true }).click();
  await expect(page.getByLabel('추가 1 · 입력', { exact: true })).toHaveValue('1 2');
  await editor.fill('abc');
  await editor.press('Control+Home');
  await editor.press('Tab');
  await expectCode(editor, '    abc');
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  await expect(editor).toBeHidden();
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expectCode(editor, '    abc');
  if (await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).isVisible()) await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).click();
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(editor).toBeHidden();
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expectCode(editor, '    abc');
});

test('mobile panes retain code and input across resizing, navigation and keyboard exit', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await editor.fill('// 모바일에서도 유지할 코드');
  await page.getByRole('button', { name: '테스트 케이스 추가', exact: true }).click();
  await page.getByRole('button', { name: '+ 케이스 추가', exact: true }).click();
  await page.getByLabel('추가 1 · 입력', { exact: true }).fill('42 58');
  await page.getByRole('button', { name: '문제 보기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '초안 테스트' })).toBeVisible();
  await expect(editor).toBeHidden();
  await page.setViewportSize({ width: 1440, height: 900 });
  await expectCode(editor, '// 모바일에서도 유지할 코드');
  await expect(page.getByLabel('추가 1 · 입력', { exact: true })).toHaveValue('42 58');
  await expect(page.getByRole('heading', { name: '초안 테스트' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '코드 작성', exact: true }).click();
  await editor.focus();
  await editor.press('Escape');
  await editor.press('Tab');
  await expect(editor).not.toBeFocused();
  await expect(page.getByRole('separator',{name:'편집기 높이 조절',exact:true})).toBeFocused();
  const tools=page.locator('#editor-tools > summary');
  await tools.focus();await tools.press('Enter');
  await expect(page.locator('#editor-tools')).toHaveAttribute('open','');
  await expect(page.getByText('편집기 단축키 · 자동완성',{exact:true})).toBeVisible();
  await tools.press('Enter');await expect(page.locator('#editor-tools')).not.toHaveAttribute('open','');
  await expectCode(editor, '// 모바일에서도 유지할 코드');
  for (const width of [360, 768, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

test('closed submissions cannot be sent through the editor shortcut', async ({ page }) => {
  await workspace(page);
  await page.route('**/api/problems', route => route.fulfill({ json: [{ version: 'v1', title: '닫힌 채점',
    statement: '테스트 문제', sampleInput: '', sampleOutput: '', submissionsEnabled: false }] }));
  let submissions = 0;
  await page.route('**/api/submissions{,?*}', async route => {
    if (route.request().method() === 'POST') submissions++;
    await route.fulfill({ json: [] });
  });
  await page.reload();
  await expect(page.getByRole('button', { name: '제출 후 채점하기', exact: true })).toBeDisabled();
  const editor = page.getByLabel('Main.java', { exact: true });
  await editor.press('Control+Enter');
  await editor.press('Meta+Enter');
  await expect(editor).toBeEnabled();
  expect(submissions).toBe(0);
});


test('workspace fits the viewport and result resizing preserves drafts and input', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  for (const [width, height] of [[1440,900], [1366,768], [1024,768], [390,844], [375,667]]) {
    await page.setViewportSize({ width, height });
    await expect(page.getByRole('button', { name: '제출 후 채점하기', exact: true })).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight + 1)).toBe(true);
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(page.locator('#global-header')).toBeVisible();
  await page.getByRole('button', { name: '제출 기록', exact: true }).click();
  await page.getByRole('button', { name: '테스트 케이스 추가', exact: true }).click();
  await page.getByRole('button', { name: '+ 케이스 추가', exact: true }).click();
  await page.getByLabel('추가 1 · 입력', { exact: true }).fill('123 456');
  await editor.fill('// preserved during panel resize');
  const slider = page.getByRole('separator', { name: '결과 패널 너비' });
  await slider.focus();
  await slider.press('End');
  await expect(page.getByRole('button', { name: '제출 후 채점하기', exact: true })).toBeInViewport({ ratio: 1 });
  await page.getByRole('button', { name: '결과 접기', exact: true }).click();
  await expect(page.locator('#workspace-results')).toBeHidden();
  await expect(page.getByLabel('추가 1 · 입력', { exact: true })).toHaveValue('123 456');
  await expectCode(editor, '// preserved during panel resize');
});

test('active training allows problem switching without linking unrelated work or resetting drafts', async ({ page }) => {
  await workspace(page);
  let refreshes = 0;
  const session = { id: 'training-v1', status: 'ACTIVE', problemVersion: 'v1', goal: '테스트', pending: 0 };
  await page.route('**/api/training-sessions', async route => {
    refreshes++;
    await route.fulfill({ json: [session] });
  });
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'test' } }));
  const requests = [];
  for (const endpoint of ['submissions', 'runs']) {
    await page.route(`**/api/${endpoint}{,?*}`, async route => {
      if (route.request().method() !== 'POST') return route.fulfill({ json: [] });
      const body = route.request().postDataJSON();
      requests.push({ endpoint, ...body });
      await route.fulfill({ status: 202, json: { id: `job-${requests.length}`, ...body, status: 'FINISHED',
        verdict: endpoint === 'runs' ? 'OK' : 'AC', createdAt: new Date().toISOString(), runnerPolicy: 'java8-judge-v1' } });
    });
  }
  await page.reload();
  const choice = page.getByLabel('풀이할 문제');
  const editor = page.getByLabel('Main.java', { exact: true });
  await expect(choice).toBeEnabled();
  await editor.fill('// 훈련 문제 초안');
  await page.getByRole('button', { name: '문제 탐색', exact: true }).click();
  await page.getByRole('button', { name: '초안 테스트 · v2 풀기', exact: true }).click();
  await editor.fill('// 다른 문제 초안');
  await expect(page.getByText('현재 문제의 제출은 자유 풀이로 저장돼요.', { exact: false })).toBeVisible();
  const before = refreshes;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect.poll(() => refreshes).toBeGreaterThan(before);
  await expect(choice).toHaveValue('v2');
  await expectCode(editor, '// 다른 문제 초안');
  await page.getByRole('button', { name: '제출 후 채점하기', exact: true }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0]).toMatchObject({ problemVersion: 'v2', sessionId: null });
  await page.getByRole('button', { name: '코드 실행', exact: true }).click();
  await expect.poll(() => requests.length).toBe(2);
  expect(requests[1]).toMatchObject({ endpoint: 'runs', problemVersion: 'v2', sessionId: null });
  await choice.selectOption('v1');
  await expectCode(editor, '// 훈련 문제 초안');
  await page.getByRole('button', { name: '제출 후 채점하기', exact: true }).click();
  await expect.poll(() => requests.length).toBe(3);
  expect(requests[2]).toMatchObject({ problemVersion: 'v1', sessionId: 'training-v1' });
});

test('problem catalog cannot replace an unconfirmed submission snapshot', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('gamjaoj-pending-draft-user-a', JSON.stringify({
    key: 'catalog-pending', problemVersion: 'v1', source: '// 접수 확인할 원본', sessionId: null,
  })));
  await workspace(page);
  await page.getByRole('button', { name: '문제 탐색', exact: true }).click();
  const catalog = page.getByRole('region', { name: '문제 목록', exact: true });
  await expect(catalog.getByRole('button', { name: '초안 테스트 · v2 풀기', exact: true })).toBeDisabled();
  await expect(catalog.getByRole('button', { name: '초안 테스트 · v1 이어서 풀기', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expectCode(page.getByLabel('Main.java', { exact: true }), '// 접수 확인할 원본');
  expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('gamjaoj-pending-draft-user-a')).problemVersion)).toBe('v1');
});

test('Java editor renders line numbers and syntax, pairs brackets, indents and undoes', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await editor.fill('class Main {\n    static int add(int left, int right) {\n        return left + right;\n    }\n}');
  await expect(page.locator('.cm-lineNumbers')).toContainText('5');
  await expect(editor.locator('.java-parameter').first()).toHaveText('left');
  const colors = await editor.evaluate(el => {
    const spans = [...el.querySelectorAll('span')];
    return ['class', 'add', 'left'].map(word => getComputedStyle(spans.find(node => node.textContent === word)).color);
  });
  expect(new Set(colors).size).toBe(3);
  await editor.fill('');
  await editor.pressSequentially('{');
  await expectCode(editor, '{}');
  await editor.press('Enter');
  await expectCode(editor, '{\n    \n}');
  await editor.pressSequentially('int count = 42;');
  await expectCode(editor, '{\n    int count = 42;\n}');
  await editor.press('ControlOrMeta+z');
  await expectCode(editor, '{}');
  await editor.press('ControlOrMeta+Shift+z');
  await expectCode(editor, '{\n    int count = 42;\n}');
  await editor.press('ControlOrMeta+f');
  await expect(page.locator('.cm-search')).toBeVisible();
  await page.keyboard.press('Escape');
  await editor.fill('');
  await page.getByRole('button', { name: '제출 후 채점하기', exact: true }).click();
  await expect(page.getByRole('region', { name: '문제 풀이', exact: true }).getByRole('alert')).toContainText('먼저 Main.java 코드를 작성');
});


test('editor keeps full long drafts and rejects oversized replacement without data loss', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  const source = Array.from({ length: 1000 }, (_, i) => `// line ${i + 1}`).join('\n');
  await editor.fill(source);
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('gamjaoj-draft-v1-draft-user-a-v1')).source)).toBe(source);
  await page.reload();
  await editor.press('ControlOrMeta+End');
  await expect(editor).toContainText('// line 1000');
  await editor.press('ControlOrMeta+a');
  await page.keyboard.insertText('safe draft');
  await expectCode(editor, 'safe draft');
  await editor.press('ControlOrMeta+a');
  await page.keyboard.insertText('x'.repeat(65537));
  await expect(page.getByRole('region', { name: '문제 풀이', exact: true }).getByRole('alert')).toContainText('기존 내용을 유지');
  await expectCode(editor, 'safe draft');
});

test('problem catalog searches real titles and IDs and preserves drafts across mobile navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await editor.fill('// 첫 문제 초안');
  await page.getByRole('button', { name: '문제 탐색', exact: true }).click();
  const catalog = page.getByRole('region', { name: '문제 목록', exact: true });
  const search = catalog.getByRole('searchbox', { name: '문제 검색' });
  await expect(editor).toBeHidden();
  await search.fill(' V2 ');
  await expect(catalog.getByRole('listitem')).toHaveCount(1);
  await expect(catalog.getByRole('status')).toHaveText('검색 결과 1개 · 전체 2개');
  await search.fill('없는문제');
  await expect(catalog.getByText('일치하는 문제가 없어요.', { exact: false })).toBeVisible();
  await catalog.getByRole('button', { name: '검색 지우기' }).click();
  await expect(catalog.getByRole('listitem')).toHaveCount(2);
  await search.fill('초안');
  await expect(catalog.getByRole('listitem')).toHaveCount(2);
  await catalog.getByRole('button', { name: '초안 테스트 · v2 풀기', exact: true }).click();
  await expect(page.locator('#problem-title')).toBeFocused();
  await expect(page.locator('#problem-title')).toBeVisible();
  await page.getByRole('button', { name: '코드 작성', exact: true }).click();
  await expectCode(editor, '// 첫 문제 초안', true);
  await editor.fill('// 두 번째 초안');
  await page.getByRole('button', { name: '문제 탐색', exact: true }).click();
  await expect(search).toHaveValue('초안');
  await catalog.getByRole('button', { name: '초안 테스트 · v1 풀기', exact: true }).click();
  await page.getByRole('button', { name: '코드 작성', exact: true }).click();
  await expectCode(editor, '// 첫 문제 초안');
  for (const width of [360, 768, 1440]) {
    await page.setViewportSize({ width, height: 844 });
    await page.getByRole('button', { name: '문제 탐색', exact: true }).click();
    await expect(catalog.getByRole('listitem')).toHaveCount(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

test('saved hints stay local and personal feedback requests use the saved submission', async ({page})=>{
  await workspace(page);
  const saved={id:'00000000-0000-0000-0000-000000000123',problemVersion:'v1',source:'class Main {}',status:'FINISHED',verdict:'WA',createdAt:'2026-09-22T00:00:00Z',runnerPolicy:'java8-judge-v1'};
  let calls=0,items=[];
  await page.route('**/api/submissions{,?*}',route=>route.fulfill({json:[saved]}));
  await page.route('**/api/submissions/'+saved.id,route=>route.fulfill({json:saved}));
  await page.route('**/api/problems/*/teaching',route=>route.fulfill({json:{hints:['입력을 읽으세요.','개수를 확인하세요.','long을 쓰세요.'],editorial:'누적합을 계산합니다.'}}));
  await page.route('**/api/ai/tasks*',async route=>{
    if(route.request().method()==='POST'){
      calls++;expect(route.request().postDataJSON()).toMatchObject({submissionId:saved.id,kind:'ANALYSIS',strong:false});
      items=[{id:'analysis',kind:'ANALYSIS',status:'HELD_DISABLED',model:'gpt-5.6-luna',effort:'low',result:null}];
      return route.fulfill({json:items[0]});
    }
    return route.fulfill({json:items});
  });
  await page.reload();
  await page.getByText('기본 힌트 1',{exact:true}).click();
  await expect(page.getByText('입력을 읽으세요.',{exact:true})).toBeVisible();
  expect(calls).toBe(0);
  await page.getByRole('button',{name:'제출 기록',exact:true}).click();
  await page.getByText('최근 제출 내역', {exact:false}).click();
  await page.locator('#submission-results .record-list button').first().click();
  await page.getByRole('button',{name:'이 제출 피드백 보기',exact:true}).click();
  const feedback=page.getByRole('region',{name:'개인 학습 피드백'});
  await expect(feedback.getByRole('button',{name:'상위 모델로 재분석'})).toHaveCount(0);
  await feedback.getByRole('button',{name:'풀이 분석 요청',exact:true}).click();
  await expect(feedback.getByText('AI 호출이 일시 중지되어 있어요.',{exact:false})).toBeVisible();
  expect(calls).toBe(1);
});

test('side panel bounds, collapsed histories and read-only record preserve the draft', async ({page})=>{
  await workspace(page);
  const records=Array.from({length:50},(_,i)=>({id:`record-${i}`,problemVersion:'v1',source:`// saved ${i}`,input:'1 2',stdout:'3',status:'FINISHED',verdict:'AC',createdAt:'2026-09-24T00:00:00Z'}));
  for(const endpoint of ['submissions','runs']){
    const items=records.map(item=>({...item,verdict:endpoint==='runs'?'OK':'AC'}));
    await page.route(`**/api/${endpoint}{,?*}`,route=>route.fulfill({json:items}));
    await page.route(`**/api/${endpoint}/record-*`,route=>route.fulfill({json:items.find(r=>route.request().url().endsWith(r.id))}));
  }
  await page.reload();
  const editor=page.getByLabel('Main.java',{exact:true});
  await editor.fill('// current draft');
  for(const width of [1920,1440,1366,1024]){
    await page.setViewportSize({width,height:900});
    await page.getByRole('button',{name:'제출 기록',exact:true}).click();
    const a=await page.locator('.editor-column').boundingBox(),b=await page.locator('#workspace-results').boundingBox();
    expect(a.x+a.width<=b.x+1||b.x+b.width<=a.x+1).toBe(true);
    expect(a.width).toBeGreaterThan(450);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }
  const history=page.locator('#submission-results .record-history');
  await expect(history).not.toHaveAttribute('open','');
  await history.locator('summary').click();
  await expect(history.locator('li')).toHaveCount(10);
  await expect(history.getByRole('navigation')).toContainText('1 /');
  await history.getByRole('button',{name:'다음',exact:true}).click();
  await expect(history.locator('li')).toHaveCount(10);
  await expect(history.getByRole('navigation')).toContainText('2 /');
  await expect(history.getByRole('button',{name:'이전',exact:true})).toBeEnabled();
  await history.getByRole('button',{name:'이전',exact:true}).click();
  await expect(history.getByRole('navigation')).toContainText('1 /');
  await history.locator('li button').first().click();
  await expect(history).not.toHaveAttribute('open','');
  await expect(page.locator('#submission-heading')).toBeInViewport();
  await page.getByRole('button',{name:'해당 제출 코드 보기'}).click();
  await expectCode(page.getByLabel('기록 코드',{exact:true}),'// saved 0');
  await expect(page.getByRole('button',{name:'제출 후 채점하기',exact:true})).toBeDisabled();
  await page.getByRole('button',{name:'작성 중인 코드로 돌아가기'}).click();
  await expectCode(editor,'// current draft');
  await page.getByRole('button',{name:'테스트 케이스 추가',exact:true}).click();
  await expect(page.locator('.run-console .record-history')).toHaveCount(0);
  await expect(page.getByRole('button',{name:'+ 케이스 추가',exact:true})).toBeVisible();
  for(const width of [390,1024,1920]){
    await page.setViewportSize({width,height:900});
    await page.screenshot({path:`/tmp/gamja-panels-${width}.png`});
  }
});

test('late submission acceptance does not reopen a dismissed panel', async ({page})=>{
  await workspace(page);
  await page.route('**/api/auth/csrf',r=>r.fulfill({json:{headerName:'X-CSRF-TOKEN',token:'test'}}));
  let release;
  const gate=new Promise(resolve=>{release=resolve;});
  let received=false;
  await page.route('**/api/submissions{,?*}',async route=>{
    if(route.request().method()!=='POST')return route.fulfill({json:[]});
    received=true;await gate;
    await route.fulfill({json:{id:'late',...route.request().postDataJSON(),status:'FINISHED',verdict:'AC',createdAt:new Date().toISOString()}});
  });
  await page.getByRole('button',{name:'제출 기록',exact:true}).click();
  await page.getByRole('button',{name:'제출 후 채점하기',exact:true}).click();
  await expect.poll(()=>received).toBe(true);
  await page.getByRole('button',{name:'결과 접기',exact:true}).click();
  release();
  await expect(page.getByText('제출한 코드를 저장했어요.',{exact:false})).toBeVisible();
  await expect(page.locator('#workspace-results')).toBeHidden();
});

test('account controls remain visible and navigation preserves editor space', async ({page})=>{
  await page.setViewportSize({width:1440,height:900});
  await workspace(page);
  const header=page.locator('#global-header');
  await expect(header).toBeVisible();
  // The sidebar starts expanded; the compact solving layout below is measured after collapsing it.
  await page.getByRole('button',{name:'사이드바 접기'}).click();
  const initial=(await page.locator('#source').boundingBox()).height;
  expect((await page.locator('#source').boundingBox()).y).toBeLessThan(160);
  expect((await page.locator('.app-navigation').boundingBox()).width).toBeLessThanOrEqual(52);
  await expect(page.locator('.workspace-heading')).toBeHidden();
  await page.locator('.problem-card').hover();
  await page.mouse.wheel(0,-50);await page.mouse.wheel(0,80);
  await expect(header).toBeVisible();
  expect((await page.locator('#source').boundingBox()).height).toBeCloseTo(initial,0);
  const explore=page.getByRole('button',{name:'문제 탐색',exact:true});
  await explore.focus();await explore.press('Enter');
  await expect(explore).toHaveAttribute('aria-pressed','true');
  await expect(page.locator('.workspace-heading')).toBeVisible();
  expect((await page.locator('.app-navigation').boundingBox()).width).toBeLessThanOrEqual(52);
  await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
  for(const width of [390,768,1440]){
    await page.setViewportSize({width,height:900});
    await expect(header).toBeVisible();
    await expect(page.getByRole('button',{name:'내 설정',exact:true})).toBeVisible();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
    expect(await page.evaluate(()=>document.documentElement.scrollHeight<=innerHeight+1)).toBe(true);
    expect((await page.locator('#source').boundingBox()).y).toBeLessThan(200);
    await page.screenshot({path:`/tmp/gamja-ui-workspace-${width}.png`});
  }
});

test('tag generation hold refreshes descendants and disables their solve actions', async ({page}) => {
  let held=false;
  await workspace(page);
  await page.route('**/api/generation',route=>route.fulfill({json:[0,1].map(index=>({
    id:`generated-${index}`,status:'READY',problemVersion:`generated-${index}-r0`,
    artifacts:{title:index?'파생 문제':'원본 문제',context:'검증된 규칙의 이야기'},
    problemHeld:held,reviewReason:held?'공유 구조 오류':'',
    preview:{contractTitle:'수열 합',structure:{category:'수열',reused:!!index}}
  }))}));
  await page.route('**/api/problems/generated-0-r0/review-hold',async route=>{
    expect(route.request().postDataJSON().reason).toBe('공유 구조 오류');held=true;
    await route.fulfill({json:{held:true,reason:'공유 구조 오류'}});
  });
  await page.reload();
  await page.getByRole('button',{name:'내 문제 생성',exact:true}).click();
  const root=page.locator('.generation-job').filter({hasText:'원본 문제'});
  await root.getByText('문제 오류 신고·풀이 보류',{exact:true}).click();
  await expect(root.getByText('이 구조를 재사용한 내 문제들도 함께 보류하며, 아직 생성 중인 문제는 게시하지 않습니다.')).toBeVisible();
  await root.getByLabel('검토 사유').fill('공유 구조 오류');
  await root.getByRole('button',{name:'이 문제 풀이 보류',exact:true}).click();
  await expect(root.getByRole('button',{name:'이 문제 풀기',exact:true})).toBeDisabled();
  const child=page.locator('.generation-job').filter({hasText:'파생 문제'});
  await child.locator('summary').first().click();
  await expect(child.getByRole('button',{name:'이 문제 풀기',exact:true})).toBeDisabled();
  await expect(child.getByText(/문제 검토 중 · 공유 구조 오류/)).toBeVisible();
});

for(const width of [390,1440])test(`local Java completion confirms explicitly and preserves whitespace at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900});await workspace(page);
  const editor=page.getByLabel('Main.java',{exact:true});
  const prefix='class Main { int totalCount; int totalSum; void sumValues() {} void run() {\n';
  const popup=page.locator('.cm-tooltip-autocomplete');
  async function type(text){await editor.fill(prefix);await editor.press('ControlOrMeta+End');await editor.pressSequentially(text);await expect(popup).toBeVisible();
    // CodeMirror ignores selection keys for 75ms after opening to avoid accidental acceptance.
    await page.waitForTimeout(100);}
  await type('tot');
  await expect(popup).toContainText('totalCount');await expect(popup).toContainText('totalSum');
  await page.screenshot({path:`/tmp/gamja-completion-${width}.png`});
  await editor.press('Space');await expectCode(editor,prefix+'tot ');
  await expect(popup).toBeHidden();
  await type('tot');await editor.press('Tab');
  await expectCode(editor,prefix+'    tot');await expect(popup).toBeHidden();
  await type('tot');await expect(popup.locator('[aria-selected="true"]')).toContainText('totalCount');
  await editor.press('ArrowDown');await expect(popup.locator('[aria-selected="true"]')).toContainText('totalSum');
  await editor.press('Enter');await expectCode(editor,prefix+'totalSum');
  await editor.press('ControlOrMeta+z');await expectCode(editor,prefix+'tot');
  await type('sumV');await editor.press('Enter');await expectCode(editor,prefix+'sumValues');
  await type('tot');await editor.press('Escape');await expect(popup).toBeHidden();
  await editor.press('Enter');
  const lines=await editor.locator('.cm-line').allTextContents();
  expect(lines).toHaveLength(3);expect(lines[1]).toBe('tot');expect(lines[2].trim()).toBe('');
  await editor.fill(prefix);await editor.press('ControlOrMeta+End');await editor.press('Control+Space');
  await expect(popup).toContainText('sumValues');await editor.press('Escape');
  for(const suffix of ['// tot','/* tot','String note = "tot']){
    await editor.fill(prefix+suffix);await editor.press('ControlOrMeta+End');await editor.press('Control+Space');
    await page.waitForTimeout(200);await expect(popup).toBeHidden();
  }
  await editor.fill('class Main { void run() {\n');await editor.press('ControlOrMeta+End');await editor.pressSequentially('tot');
  await editor.press('Control+Space');await page.waitForTimeout(200);
  await expect(popup.getByRole('option').filter({hasText:'totalCount'})).toHaveCount(0);
  await expect(popup.getByRole('option').filter({hasText:'totalSum'})).toHaveCount(0);
});

for(const width of [390,1440])test(`Java standard classes complete without committing on whitespace at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900});await workspace(page);
  const editor=page.getByLabel('Main.java',{exact:true}),popup=page.locator('.cm-tooltip-autocomplete');
  const prefix='class Main { void run() {\n';
  async function type(word){await editor.fill(prefix);await editor.press('ControlOrMeta+End');await editor.pressSequentially(word);await expect(popup).toBeVisible();await page.waitForTimeout(100);}
  for(const [typed,name,pkg] of [['BufferedR','BufferedReader','java.io'],['ArrayL','ArrayList','java.util'],['BigInt','BigInteger','java.math'],['Strin','String','java.lang']]){
    await type(typed);const option=popup.getByRole('option').filter({hasText:name}).first();
    await expect(option).toContainText(pkg);
    if(name==='BufferedReader'){
      await expect(page.locator('.cm-completionInfo')).toBeInViewport({ratio:1});
      await page.screenshot({path:`/tmp/gamja-class-completion-${width}.png`});
    }
    await editor.press('Enter');await expectCode(editor,prefix+name);
    await expect(editor).not.toContainText('import ');
  }
  await editor.fill('class CustomNode {}\n'+prefix);await editor.press('ControlOrMeta+End');await editor.pressSequentially('CustomN');
  await expect(popup).toContainText('문서 내 타입');await page.waitForTimeout(100);
  await editor.press('Enter');await expectCode(editor,'class CustomNode {}\n'+prefix+'CustomNode');
  await type('BufferedR');await editor.press('Space');await expectCode(editor,prefix+'BufferedR ');
  await expect(popup).toBeHidden();
  await type('BufferedR');await editor.press('Tab');await expectCode(editor,prefix+'    BufferedR');
  await expect(popup).toBeHidden();
  for(const suffix of ['// BufferedR','/* BufferedR','String s = "BufferedR']){
    await editor.fill(prefix+suffix);await editor.press('ControlOrMeta+End');await editor.press('Control+Space');
    await page.waitForTimeout(200);await expect(popup).toBeHidden();
  }
});

for(const width of [390,1440])test(`selected code stays legible and replacement undo preserves source at ${width}`,async({page})=>{
  await page.setViewportSize({width,height:900});await workspace(page);
  const editor=page.getByLabel('Main.java',{exact:true});
  const source='class Main {\n    // selected comments and strings remain readable\n    String name = "hello";\n    int count = 123;\n}';
  await editor.fill(source);
  const line=await editor.locator('.cm-line').first().boundingBox();
  await page.mouse.move(line.x+12,line.y+8);await page.mouse.down();await page.mouse.move(line.x+120,line.y+8,{steps:6});await page.mouse.up();
  await expect(page.locator('#source .cm-selected-code').first()).toBeVisible();
  await editor.press('ControlOrMeta+a');
  await expect(page.locator('#source .cm-selectionBackground').first()).toBeVisible();
  const colors=await page.locator('#source').evaluate(root=>({
    background:getComputedStyle(root.querySelector('.cm-selectionBackground')).backgroundColor,
    foreground:[...root.querySelectorAll('.cm-selected-code, .cm-selected-code *')].map(el=>getComputedStyle(el).color),
  }));
  expect(colors.background).toBe('rgb(255, 224, 138)');
  expect(colors.foreground.length).toBeGreaterThan(0);expect(new Set(colors.foreground)).toEqual(new Set(['rgb(24, 35, 45)']));
  await page.screenshot({path:`/tmp/gamja-selection-${width}.png`});
  await editor.pressSequentially('replacement');await expectCode(editor,'replacement');
  await editor.press('ControlOrMeta+z');await expectCode(editor,source);
  await editor.press('ArrowRight');await expect(page.locator('#source .cm-selected-code')).toHaveCount(0);
});

for(const width of [390,1024,1440])test('editor size persists without changing code at '+width,async({page})=>{
 await page.setViewportSize({width,height:900});const switchUser=await workspace(page);
 const editor=page.getByLabel('Main.java',{exact:true});await editor.fill('// resize keeps this draft');
 if(width>800){
  const split=page.getByRole('separator',{name:'문제와 편집기 비율',exact:true});
  const before=(await page.locator('.problem-card').boundingBox()).width;
  const b=await split.boundingBox();await page.mouse.move(b.x+b.width/2,b.y+40);await page.mouse.down();await page.mouse.move(b.x+b.width/2+60,b.y+40,{steps:6});await page.mouse.up();
  expect((await page.locator('.problem-card').boundingBox()).width).toBeGreaterThan(before+25);
  await split.focus();await split.press('Home');await expect(split).toHaveAttribute('aria-valuenow','20');
 }
 const height=page.getByRole('separator',{name:'편집기 높이 조절',exact:true});
 await height.focus();await height.press('Home');
 expect((await page.locator('#source').boundingBox()).height).toBeCloseTo(160,0);
 const b=await height.boundingBox();await page.mouse.move(b.x+40,b.y+b.height/2);await page.mouse.down();await page.mouse.move(b.x+40,b.y+b.height/2+80,{steps:8});await page.mouse.up();
 expect((await page.locator('#source').boundingBox()).height).toBeCloseTo(240,0);
 if(width===1024){
  await page.getByRole('button',{name:'제출 기록',exact:true}).click();
  const resultSplit=page.getByRole('separator',{name:'결과 패널 너비',exact:true});
  const prior=(await page.locator('.result-dock').boundingBox()).width;
  await resultSplit.focus();await resultSplit.press('ArrowRight');await expect(resultSplit).toHaveAttribute('aria-valuenow','380');
  expect((await page.locator('.result-dock').boundingBox()).width).toBeGreaterThan(prior+15);
  await page.getByRole('button',{name:'결과 접기',exact:true}).click();
 }
 await page.reload();await expectCode(editor,'// resize keeps this draft');
 expect((await page.locator('#source').boundingBox()).height).toBeCloseTo(240,0);
 if(width>800)await expect(page.getByRole('separator',{name:'문제와 편집기 비율',exact:true})).toHaveAttribute('aria-valuenow','20');
 await height.focus();await height.press('End');
 await expect(page.getByRole('button',{name:'제출 후 채점하기',exact:true})).toBeInViewport({ratio:1});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await expectCode(editor,'// resize keeps this draft');
 await height.focus();await height.press('Home');await switchUser('draft-user-b');
 await expect(page.getByRole('separator',{name:'편집기 높이 조절',exact:true})).not.toHaveAttribute('aria-valuenow','160');
 await switchUser('draft-user-a');await expectCode(editor,'// resize keeps this draft');
 await expect(page.getByRole('separator',{name:'편집기 높이 조절',exact:true})).toHaveAttribute('aria-valuenow','160');
 await page.screenshot({path:`/tmp/gamja-resize-${width}.png`,fullPage:true});
});
