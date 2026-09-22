import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
async function remotePython(program) {
  // Keep credentials in the remote process; no token is returned to the browser test.
  const child = execFile('ssh', ['-o', 'BatchMode=yes', 'ocr-serv', 'python3', '-']);
  let out = '', err = '';
  child.stdout.on('data', data => { out += data; });
  child.stderr.on('data', data => { err += data; });
  const finished = new Promise((resolve, reject) => child.on('exit', code => code === 0 ? resolve(out) : reject(new Error(err))));
  child.stdin.end(program);
  return finished;
}

test('signup, login, personal settings, reload and logout in a real browser', async ({ page, browser }) => {
  test.setTimeout(90000);
  const username = process.env.GAMJAOJ_E2E_USERNAME;
  const invitation = process.env.INVITE_CODE;
  test.skip(!username || !invitation, 'Run through scripts/test-browser-auth.sh');
  const base = process.env.GAMJAOJ_BASE_URL || 'http://192.168.0.210:18081';
  const password = 'Browser-test-only-429!';
  expect(username).toMatch(/^[a-z0-9_]{3,24}$/);
  const code = readFileSync('../examples/Main.java', 'utf8');
  const hash = createHash('sha256').update(code).digest('hex');
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base);
  await expect(page.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  await page.getByRole('button', { name: '처음 왔어요' }).click();
  await page.getByLabel('닉네임', { exact: true }).fill('브라우저 감자');
  await page.getByLabel('아이디', { exact: true }).fill(username);
  await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByLabel('비밀번호 확인', { exact: true }).fill(password);
  await page.getByLabel('초대코드', { exact: true }).fill(invitation);
  await page.getByRole('button', { name: '가입하기', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('가입했어요');
  await remotePython(`import subprocess
from pathlib import Path
config=dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#'))
if config.get('SUBMISSIONS_ENABLED','false').lower() != 'true':
 subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-v','ON_ERROR_STOP=1'],
  input="INSERT INTO execution_grant (user_id,source_sha256) SELECT id,'${hash}' FROM app_user WHERE username='${username}';",text=True,check=True)
`);
  await page.getByLabel('아이디', { exact: true }).fill(username);
  await page.getByLabel('비밀번호', { exact: true }).fill('incorrect-password');
  await page.getByRole('button', { name: '내 연습장으로' }).click();
  await expect(page.getByRole('alert').filter({ hasText: '아이디 또는 비밀번호' })).toBeVisible();
  await page.getByLabel('비밀번호', { exact: true }).fill(password);
  await page.getByRole('button', { name: '내 연습장으로' }).click();
  await expect(page.getByLabel('Main.java', { exact: true })).toBeInViewport();
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(page.getByRole('heading', { name: '브라우저 감자님, 반가워요.' })).toBeVisible();
  const session = (await page.context().cookies()).find(cookie => cookie.name === 'GAMJAOJ_SESSION');
  expect(session).toBeDefined();
  expect(session.httpOnly).toBe(true);
  expect(session.sameSite).toBe('Lax');
  if (base.startsWith('https://')) expect(session.secure).toBe(true);
  await page.getByLabel('연습하고 싶은 목표').fill('DP 점화식 세우기');
  await page.getByRole('button', { name: '내 설정 저장' }).click();
  await expect(page.getByRole('status')).toContainText('저장했어요');
  await page.reload();
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(page.getByLabel('연습하고 싶은 목표')).toHaveValue('DP 점화식 세우기');
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '두 정수의 합' })).toBeVisible();
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  const training = page.getByRole('region', { name: '훈련 세션', exact: true });
  await training.getByLabel('이번 훈련 목표').fill('입출력과 경계값 확인');
  let lostStart = false;
  const sessionKeys = [];
  await page.route('**/api/training-sessions', async route => {
    if (route.request().method() === 'POST') {
      sessionKeys.push(route.request().headers()['idempotency-key']);
      if (!lostStart) {
        lostStart = true;
        expect((await route.fetch()).status()).toBe(200);
        await route.abort(); return;
      }
    }
    await route.continue();
  });
  await training.getByRole('button', { name: '훈련 시작', exact: true }).click();
  await expect(training.getByRole('button', { name: '같은 훈련 요청 다시 확인' })).toBeEnabled();
  await page.reload();
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  await training.getByRole('button', { name: '같은 훈련 요청 다시 확인' }).click();
  await expect(training.getByRole('button', { name: '훈련 마치기' })).toBeEnabled();
  expect(sessionKeys).toHaveLength(2);
  expect(sessionKeys[0]).toBe(sessionKeys[1]);
  await page.unroute('**/api/training-sessions');
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await page.getByText('파일 불러오기 / 내려받기', { exact: true }).click();
  await page.getByLabel('Java 파일 불러오기').setInputFiles({ name: 'Solution.java', mimeType: 'text/plain', buffer: Buffer.from(code) });
  await expect(page.getByLabel('Main.java', { exact: true })).toHaveValue(code);
  await page.reload();
  await expect(page.getByLabel('Main.java', { exact: true })).toHaveValue(code);
  let lost = false, acceptedId;
  const submittedKeys = [];
  await page.route('**/api/submissions', async route => {
    if (route.request().method() === 'POST') {
      submittedKeys.push(route.request().headers()['idempotency-key']);
      if (!lost) {
        lost = true;
        const response = await route.fetch();
        expect(response.status()).toBe(202);
        acceptedId = (await response.json()).id;
        await route.abort();
        return;
      }
    }
    await route.continue();
  });
  await page.getByRole('button', { name: '코드 제출', exact: true }).click();
  await expect(page.getByRole('button', { name: '같은 제출 다시 확인' })).toBeEnabled();
  const newerDraft = code + '\n// 제출 응답을 기다리며 작성한 다음 초안\n';
  await page.getByLabel('Main.java', { exact: true }).fill(newerDraft);
  await page.reload();
  await expect(page.getByLabel('Main.java', { exact: true })).toHaveValue(newerDraft);
  await page.getByRole('button', { name: '같은 제출 다시 확인' }).click();
  await expect(page.getByLabel('저장된 제출 코드')).toHaveText(code.trim());
  await expect(page.getByLabel('Main.java', { exact: true })).toHaveValue(newerDraft);
  expect(submittedKeys).toHaveLength(2);
  expect(submittedKeys[1]).toBe(submittedKeys[0]);
  await page.unroute('**/api/submissions');
  await remotePython(`import os,sys,subprocess
from pathlib import Path
root=(Path.home()/'gamjaoj/web/current').resolve()
config=dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#'))
env=dict(os.environ,GAMJAOJ_API_URL='http://'+config['BIND_ADDRESS']+':'+config['HTTP_PORT'],WORKER_TOKEN=config['WORKER_TOKEN'])
if config.get('SUBMISSIONS_ENABLED','false').lower() != 'true':
 subprocess.run([sys.executable,'-m','runner.worker','--state-dir',str(root/'.state/browser-${username}'),'--once'],cwd=root,env=env,stdin=subprocess.DEVNULL,check=True,timeout=90)
`);
  await expect(page.getByText('AC · 정답', { exact: true }).first()).toBeVisible({ timeout: 15000 });
  await expect(page.locator('.submission-detail').getByText('AC · 정답', { exact: true })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: '제출 기록', exact: true }).click();
  await page.getByRole('button').filter({ hasText: 'sum-v1' }).click();
  await expect(page.getByLabel('저장된 제출 코드')).toHaveText(code.trim());
  await expect(page.getByText(`제출 ID: ${acceptedId}`, { exact: true })).toBeVisible();
  const rows = await remotePython(`import subprocess
r=subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(*) FROM submission s JOIN app_user u ON u.id=s.user_id WHERE u.username='${username}'"],capture_output=True,text=True,check=True,stdin=subprocess.DEVNULL)
print(r.stdout.strip())
`);
  expect(rows.trim()).toBe('1');
  await page.getByRole('button', { name: '실행 테스트', exact: true }).click();
  const runPanel = page.getByRole('region', { name: '직접 입력 실행', exact: true });
  await page.getByLabel('직접 입력', { exact: true }).fill('17 25\n');
  let lostRun = false;
  const runKeys = [];
  await page.route('**/api/runs', async route => {
    if (route.request().method() === 'POST') {
      runKeys.push(route.request().headers()['idempotency-key']);
      if (!lostRun) {
        lostRun = true;
        expect((await route.fetch()).status()).toBe(202);
        await route.abort(); return;
      }
    }
    await route.continue();
  });
  await runPanel.getByRole('button', { name: '직접 실행', exact: true }).click();
  await expect(runPanel.getByRole('button', { name: '같은 실행 다시 확인' })).toBeEnabled();
  await page.reload();
  await page.getByLabel('직접 입력', { exact: true }).fill('100 200\n');
  await runPanel.getByRole('button', { name: '같은 실행 다시 확인' }).click();
  await expect(page.getByLabel('실행 표준 출력')).toHaveText('42', { timeout: 20000 });
  await expect(page.getByLabel('실행한 입력')).toHaveText('17 25');
  expect(runKeys).toHaveLength(2);
  expect(runKeys[1]).toBe(runKeys[0]);
  await page.unroute('**/api/runs');
  await page.reload();
  await runPanel.getByRole('button').filter({ hasText: 'sum-v1' }).click();
  await expect(page.getByLabel('실행 표준 출력')).toHaveText('42');
  const counts = await remotePython(`import subprocess
r=subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(*) FILTER (WHERE run_input IS NULL),count(*) FILTER (WHERE run_input IS NOT NULL) FROM submission s JOIN app_user u ON u.id=s.user_id WHERE u.username='${username}'"],capture_output=True,text=True,check=True,stdin=subprocess.DEVNULL)
print(r.stdout.strip())
`);
  expect(counts.trim()).toBe('1|1');
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  await training.getByLabel('마무리 메모').fill('long으로 경계값을 처리했다.');
  let lostEnd = false;
  await page.route('**/api/training-sessions/*/end', async route => {
    if (!lostEnd) {
      lostEnd = true;
      expect((await route.fetch()).status()).toBe(200);
      await route.abort(); return;
    }
    await route.continue();
  });
  await training.getByRole('button', { name: '훈련 마치기' }).click();
  await expect(training.getByRole('button', { name: '같은 훈련 요청 다시 확인' })).toBeEnabled();
  await page.reload();
  await page.getByRole('button', { name: '훈련 기록', exact: true }).click();
  await training.getByRole('button', { name: '같은 훈련 요청 다시 확인' }).click();
  await expect(training.getByLabel('저장된 마무리 메모')).toHaveText('long으로 경계값을 처리했다.');
  await expect(training.locator('.training-detail')).toContainText('정식 제출 1회 · 정답 1회 · 직접 실행 1회 · 처리 중 0개');
  await training.getByRole('button').filter({ hasText: '정식 제출 · AC' }).click();
  await expect(training.getByLabel('훈련에 저장된 코드')).toHaveText(code.trim());
  const linked = await remotePython(`import subprocess
r=subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(DISTINCT t.id),count(s.id),min(t.status) FROM training_session t JOIN app_user u ON u.id=t.user_id LEFT JOIN submission s ON s.training_session_id=t.id WHERE u.username='${username}'"],capture_output=True,text=True,check=True,stdin=subprocess.DEVNULL)
print(r.stdout.strip())
`);
  expect(linked.trim()).toBe('1|2|ENDED');
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await page.screenshot({ path: '../.state/browser-auth-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.screenshot({ path: '../.state/browser-workspace-mobile.png', fullPage: true });
  const anonymous = await browser.newContext();
  const other = await anonymous.newPage();
  await other.goto(base);
  await expect(other.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  await anonymous.close();
  await page.getByRole('button', { name: '로그아웃', exact: true }).click();
  await expect(page.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  await page.setViewportSize({ width: 375, height: 812 });
  await expect(page.getByRole('button', { name: '내 연습장으로' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.screenshot({ path: '../.state/browser-auth-mobile.png', fullPage: true });
  expect(errors).toEqual([]);
});
