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
  await page.goto(base);
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
  await expect(editor).toHaveValue('// 첫 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v2');
  await expect(editor).not.toHaveValue('// 첫 번째 문제의 초안');
  await editor.fill('// 두 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v1');
  await expect(editor).toHaveValue('// 첫 번째 문제의 초안');
  await switchUser('draft-user-b');
  await expect(editor).not.toHaveValue('// 첫 번째 문제의 초안');
  await editor.fill('// 다른 계정');
  await switchUser('draft-user-a');
  await expect(editor).toHaveValue('// 첫 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v2');
  await expect(editor).toHaveValue('// 두 번째 문제의 초안');
  await page.getByLabel('풀이할 문제').selectOption('v1');
  await editor.fill('');
  await page.reload();
  await expect(editor).toHaveValue('');
});

test('invalid file imports preserve the draft; valid UTF-8 source downloads unchanged', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await page.getByText('파일 불러오기 / 내려받기', { exact: true }).click();
  const file = page.getByLabel('Java 파일 불러오기');
  const source = '// 한글 코드\npublic class Main {}\n';
  await file.setInputFiles({ name: 'Solution.java', mimeType: 'text/plain', buffer: Buffer.from(source) });
  await expect(editor).toHaveValue(source);
  for (const [name, buffer] of [['Main.txt', Buffer.from('wrong extension')],
    ['Main.java', Buffer.alloc(65537, 65)], ['Main.java', Buffer.from([0xff, 0xfe, 0x80])]]) {
    await file.setInputFiles({ name, mimeType: 'text/plain', buffer });
    await expect(page.getByRole('region', { name: '문제 풀이' }).getByRole('alert')).toBeVisible();
    await expect(editor).toHaveValue(source);
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
  await expect(page.getByLabel('Main.java', { exact: true })).toHaveValue('// 저장 실패에도 편집 유지');
});

test('workspace opens on the editor, initializes sample input and preserves edits across tool views', async ({ page }) => {
  await workspace(page);
  const editor = page.getByLabel('Main.java', { exact: true });
  await expect(editor).toBeInViewport();
  await expect(page.getByLabel('직접 입력', { exact: true })).toHaveValue('1 2');
  await editor.fill('abc');
  await editor.evaluate(field => field.setSelectionRange(0, 0));
  await editor.press('Tab');
  await expect(editor).toHaveValue('    abc');
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  await expect(editor).toBeHidden();
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expect(editor).toHaveValue('    abc');
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(editor).toBeHidden();
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expect(editor).toHaveValue('    abc');
});
