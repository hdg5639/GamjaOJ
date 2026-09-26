import { expectCode } from './editor-helpers.mjs';
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
async function remotePython(program) {
  // Keep credentials in the remote process; no token is returned to the browser test.
  if (!process.env.GAMJAOJ_APP_SSH_TARGET) throw new Error('Set GAMJAOJ_APP_SSH_TARGET in your private environment');
  const child = execFile('ssh', ['-o', 'BatchMode=yes', '-o', 'ControlPath=none', '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=10', '-o', 'ServerAliveCountMax=2', process.env.GAMJAOJ_APP_SSH_TARGET, 'python3', '-'], {timeout:45000,killSignal:'SIGKILL'});
  let out = '', err = '';
  child.stdout.on('data', data => { out += data; });
  child.stderr.on('data', data => { err += data; });
  const finished = new Promise((resolve, reject) => { child.once('error',reject); child.once('close', code => code === 0 ? resolve(out) : reject(new Error(err || 'Remote verification did not complete'))); });
  child.stdin.end(program);
  return finished;
}

test('signup, login, personal settings, reload and logout in a real browser', async ({ page, browser }) => {
  test.setTimeout(90000);
  const username = process.env.GAMJAOJ_E2E_USERNAME;
  const invitation = process.env.INVITE_CODE;
  test.skip(!username || !invitation, 'Run through scripts/test-browser-auth.sh');
  const base = process.env.GAMJAOJ_BASE_URL;
  if (!base) throw new Error('Set GAMJAOJ_BASE_URL');
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
  await expect(page.getByRole('heading',{name:'오늘 풀 문제를 골라보세요.'})).toBeVisible();
  await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
  await expect(page.getByLabel('Main.java', { exact: true })).toBeInViewport();
  if (await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).isVisible()) await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).click();
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(page.getByRole('heading', { name: '내 설정', exact: true })).toBeVisible();
  const session = (await page.context().cookies()).find(cookie => cookie.name === 'GAMJAOJ_SESSION');
  expect(session).toBeDefined();
  expect(session.httpOnly).toBe(true);
  expect(session.sameSite).toBe('Lax');
  if (base.startsWith('https://')) expect(session.secure).toBe(true);
  await page.getByLabel('연습하고 싶은 목표').fill('DP 점화식 세우기');
  await page.getByRole('button', { name: '내 설정 저장' }).click();
  await expect(page.getByRole('status')).toContainText('저장했어요');
  await page.reload();
  await expect(page.getByLabel('Main.java', {exact:true})).toBeVisible();
  if (await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).isVisible()) await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).click();
  await page.getByRole('button', { name: '내 설정', exact: true }).click();
  await expect(page.getByLabel('연습하고 싶은 목표')).toHaveValue('DP 점화식 세우기');
  await page.getByRole('button', { name: '문제 풀기', exact: true }).click();
  await expect(page.getByRole('heading', { name: '두 정수의 합' })).toBeVisible();
  const problemChoice = page.getByLabel('풀이할 문제');
  await problemChoice.selectOption('total-v1');
  await expect(page.getByRole('heading', { name: '수열의 합', exact: true })).toBeVisible();
  await page.getByLabel('Main.java', { exact: true }).fill('// 수열 문제 전용 초안');
  await expect(page.getByLabel('직접 입력', { exact: true })).toHaveValue('5\n1 2 3 4 5\n');
  await problemChoice.selectOption('valid-parentheses-v1');
  await expect(page.getByRole('heading', { name: '올바른 괄호', exact: true })).toBeVisible();
  await expectCode(page.getByLabel('Main.java', { exact: true }), '// 수열 문제 전용 초안', true);
  await expect(page.getByLabel('직접 입력', { exact: true })).toHaveValue('(())()\n');
  await problemChoice.selectOption('total-v1');
  await expectCode(page.getByLabel('Main.java', { exact: true }), '// 수열 문제 전용 초안');
  await problemChoice.selectOption('sum-v1');

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
  await expect(problemChoice).toBeEnabled();
  await problemChoice.selectOption('total-v1');
  await expect(page.getByText('현재 문제의 제출은 자유 풀이로 저장돼요.', { exact: false })).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(problemChoice).toHaveValue('total-v1');
  await problemChoice.selectOption('sum-v1');
  await page.getByText('파일 불러오기 / 내려받기', { exact: true }).click();
  await page.getByLabel('Java 파일 불러오기').setInputFiles({ name: 'Solution.java', mimeType: 'text/plain', buffer: Buffer.from(code) });
  await expectCode(page.getByLabel('Main.java', { exact: true }), code);
  await page.reload();
  await expectCode(page.getByLabel('Main.java', { exact: true }), code);
  let lost = false, acceptedId;
  const submittedKeys = [];
  await page.route('**/api/submissions{,?*}', async route => {
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
  await expectCode(page.getByLabel('Main.java', { exact: true }), newerDraft);
  await page.getByRole('button', { name: '같은 제출 다시 확인' }).click();
  await expect(page.getByLabel('저장된 제출 코드')).toHaveText(code.trim());
  await expectCode(page.getByLabel('Main.java', { exact: true }), newerDraft);
  expect(submittedKeys).toHaveLength(2);
  expect(submittedKeys[1]).toBe(submittedKeys[0]);
  await page.unroute('**/api/submissions{,?*}');
  await remotePython(`import os,sys,subprocess
from pathlib import Path
root=(Path.home()/'gamjaoj/web/current').resolve()
config=dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#'))
env=dict(os.environ,GAMJAOJ_API_URL='http://'+config['BIND_ADDRESS']+':'+config['HTTP_PORT'],WORKER_TOKEN=config['WORKER_TOKEN'])
if config.get('SUBMISSIONS_ENABLED','false').lower() != 'true':
 subprocess.run([sys.executable,'-m','runner.worker','--state-dir',str(root/'.state/browser-${username}'),'--once'],cwd=root,env=env,stdin=subprocess.DEVNULL,check=True,timeout=90)
`);
  await expect(page.locator('#submission-heading')).toHaveText('AC · 정답', { timeout: 15000 });
  await expect(page.locator('.submission-detail').getByText('AC · 정답', { exact: true })).toBeVisible();
  await page.getByRole('button',{name:'문제 탐색',exact:true}).click();
  await page.getByLabel('내 풀이 상태').selectOption('SOLVED');
  const solvedProblems=page.locator('.catalog-list li');
  await expect(solvedProblems).toHaveCount(1);
  await expect(solvedProblems.first()).toContainText('sum-v1');
  await expect(solvedProblems.first().locator('.catalog-progress')).toHaveText('해결');
  await page.screenshot({path:'../.state/browser-progress-production.png',fullPage:true});
  await page.getByRole('button',{name:'문제 풀기',exact:true}).click();
  await page.reload();
  await page.getByRole('button', { name: '제출 기록', exact: true }).click();
  await page.getByText('최근 제출 내역', {exact:false}).click();
  await page.getByRole('button').filter({ hasText: 'sum-v1' }).click();
  await expect(page.getByLabel('저장된 제출 코드')).toHaveText(code.trim());
  await expect(page.locator('#submission-heading')).toHaveText('AC · 정답');
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
  await page.getByRole('button', { name: '실행 테스트', exact: true }).click();
  await page.getByLabel('직접 입력', { exact: true }).fill('100 200\n');
  await runPanel.getByRole('button', { name: '같은 실행 다시 확인' }).click();
  await expect(page.getByLabel('실행 표준 출력')).toHaveText('42', { timeout: 20000 });
  await expect(page.getByLabel('실행한 입력')).toHaveText('17 25');
  expect(runKeys).toHaveLength(2);
  expect(runKeys[1]).toBe(runKeys[0]);
  await page.unroute('**/api/runs');
  await page.reload();
  await page.getByRole('button', { name: '실행 테스트', exact: true }).click();
  await expect(runPanel.getByText('최근 실행 내역',{exact:false})).toHaveCount(0);
  await expect(page.getByLabel('실행 표준 출력')).toHaveCount(0);
  const counts = await remotePython(`import subprocess
r=subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(*) FILTER (WHERE run_input IS NULL),count(*) FILTER (WHERE run_input IS NOT NULL) FROM submission s JOIN app_user u ON u.id=s.user_id WHERE u.username='${username}'"],capture_output=True,text=True,check=True,stdin=subprocess.DEVNULL)
print(r.stdout.strip())
`);
  expect(counts.trim()).toBe('1|1');
  await page.getByRole('button',{name:'마이페이지',exact:true}).click();
  const activity=page.getByRole('region',{name:'마이페이지',exact:true});
  await expect(activity.getByText('두 정수의 합',{exact:true})).toBeVisible();
  const summary=await page.evaluate(()=>fetch('/api/my/summary').then(r=>r.json()));
  expect(summary).toMatchObject({submitted:1,attemptedProblems:1,solvedProblems:1});
  expect(await page.evaluate(()=>fetch('/api/runs').then(r=>r.json()))).toEqual([]);

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
  await expect(training.locator('.training-detail')).toContainText('정식 제출 1회 · 정답 1회 · 처리 중 0개');
  await training.getByRole('button').filter({ hasText: '정식 제출 · AC' }).click();
  await expect(training.getByLabel('훈련에 저장된 코드')).toHaveText(code.trim());
  const linked = await remotePython(`import subprocess
r=subprocess.run(['docker','exec','-i','gamjaoj-postgres-1','psql','-U','gamjaoj','-d','gamjaoj','-At','-c',"SELECT count(DISTINCT t.id),count(s.id),min(t.status) FROM training_session t JOIN app_user u ON u.id=t.user_id LEFT JOIN submission s ON s.training_session_id=t.id WHERE u.username='${username}'"],capture_output=True,text=True,check=True,stdin=subprocess.DEVNULL)
print(r.stdout.strip())
`);
  expect(linked.trim()).toBe('1|2|ENDED');
  await page.getByRole('button', { name: '선택 진단', exact: true }).click();
  await expect(page.getByRole('heading', {name:'선택 진단',exact:true})).toBeVisible();
  const diagnosticBanks=await page.request.get(base+'/api/diagnostics/banks');
  expect(diagnosticBanks.ok()).toBe(true);
  const availableBanks=await diagnosticBanks.json();
  if(availableBanks.length===0)await expect(page.getByText(/검토가 끝난 진단 문항을 준비/)).toBeVisible();
  if(availableBanks.some(b=>b.id==='core-a-v2')){
    const pilot=page.getByRole('group',{name:'핵심 시범 진단 A · 8문항'});
    await expect(pilot.getByText(/난이도는 잠정 분류/)).toBeVisible();
    await pilot.getByRole('button',{name:'선택한 8문항 시작'}).click();
    await expect(page.getByRole('heading',{name:'분이 지난 뒤의 시각',exact:true})).toBeVisible();
    await page.getByRole('button',{name:'일시정지',exact:true}).click();
    await expect(page.getByRole('button',{name:'진단 이어서 풀기'})).toBeVisible();
    if(process.env.GAMJAOJ_EXPECT_B_PILOT==='1'){
      await page.getByRole('button',{name:'진단 이어서 풀기'}).click();
      for(let i=0;i<8;i++)await page.getByRole('button',{name:'모르겠어요 · 건너뛰기'}).click();
      await page.getByRole('button',{name:'재평가 가능한 분야 확인'}).click();
      const reassessment=page.getByRole('group',{name:'핵심 시범 재평가 B',exact:true});
      await reassessment.getByRole('checkbox').first().check();
      await reassessment.getByRole('button',{name:'선택한 2문항 재평가 시작'}).click();
      await expect(page.getByRole('heading',{name:'알림을 울린 시각',exact:true})).toBeVisible();
      await page.getByRole('button',{name:'이 문제나 풀이를 본 적 있어요 · 기록 후 건너뛰기'}).click();
      await expect(page.getByRole('heading',{name:'보관함의 재고',exact:true})).toBeVisible();
      await page.getByRole('button',{name:'일시정지',exact:true}).click();
    }
  }
  await page.screenshot({path:'../.state/browser-diagnostic-production.png',fullPage:true});
  await page.getByRole('button',{name:'일반 연습으로'}).click();
  if(process.env.GAMJAOJ_EXPECT_LANGUAGES==='1') {
    test.setTimeout(150000);
    await page.getByLabel('풀이할 문제').selectOption('sum-v1');
    const programs=[['CPP','Main.cpp','#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}',3000],
      ['PYTHON','Main.py','a,b=map(int,input().split());print(a+b)',8000]];
    for(const [language,file,source,timeLimit] of programs) {
      await page.getByLabel('풀이 언어',{exact:true}).selectOption(language);
      await page.getByLabel(file,{exact:true}).fill(source);
      const acceptance=page.waitForResponse(r=>r.url().endsWith('/api/submissions')&&r.request().method()==='POST');
      await page.getByRole('button',{name:'코드 제출',exact:true}).click();
      const response=await acceptance;expect(response.status()).toBe(202);
      const saved=await response.json();expect(saved.language).toBe(language);expect(saved.execution.timeLimitMs).toBe(timeLimit);
      await expect(page.locator('#submission-heading')).toHaveText('AC · 정답',{timeout:30000});
      await page.getByRole('button',{name:'입력 테스트',exact:true}).click();
      const expectedOutput=language==='CPP'?'43':'44';
      await page.getByLabel('직접 입력',{exact:true}).fill(`17 ${Number(expectedOutput)-17}`);
      const runAcceptance=page.waitForResponse(r=>r.url().endsWith('/api/runs')&&r.request().method()==='POST');
      await page.getByRole('button',{name:'직접 실행',exact:true}).click();
      const runResponse=await runAcceptance;expect(runResponse.status()).toBe(202);
      expect((await runResponse.json()).language).toBe(language);
      await expect(page.getByLabel('실행 표준 출력')).toHaveText(expectedOutput,{timeout:30000});
    }
    await page.screenshot({path:'../.state/browser-languages-production.png',fullPage:true});
  }
  await page.screenshot({ path: '../.state/browser-auth-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await page.screenshot({ path: '../.state/browser-workspace-mobile.png', fullPage: true });
  const anonymous = await browser.newContext();
  const other = await anonymous.newPage();
  await other.goto(base);
  await expect(other.getByRole('heading', { name: '다시 만나 반가워요.' })).toBeVisible();
  await anonymous.close();
  if (await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).isVisible()) await page.getByRole('button', {name:'상단 계정 메뉴 열기',exact:true}).click();
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
