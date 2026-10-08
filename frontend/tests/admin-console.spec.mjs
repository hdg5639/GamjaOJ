import { test, expect } from "@playwright/test";
const base =
  (process.env.GAMJAOJ_BASE_URL || "http://127.0.0.1:18790") +
  "/admin-console.html";
async function fixture(page, { verified = true, expireNotice = false } = {}) {
  page.on("pageerror", (e) => console.log("Browser error:", e.message));
  let verifiedNow = verified,
    expire = expireNotice;
  const notices = [],
    keys = [];
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    let json = {};
    if (path === "/api/auth/csrf")
      json = { headerName: "X-CSRF-TOKEN", token: "browser-fixture" };
    else if (path === "/api/admin/me")
      json = {
        username: "operator",
        verified: verifiedNow,
        verifiedUntil: Date.now() + 900000,
        bootstrap: true,
      };
    else if (path === "/api/admin/session/verify") {
      verifiedNow = true;
      json = {
        username: "operator",
        verified: true,
        verifiedUntil: Date.now() + 900000,
        bootstrap: true,
      };
    } else if (path === "/api/admin/overview")
      json = {
        measuredAt: "2026-10-08T12:00:00Z",
        members: 3,
        problems: 5,
        judgeQueue: { QUEUED: 2, RUNNING: 1 },
        generationJobs: { READY: 3 },
        aiTasks: { FAILED: 1 },
      };
    else if (path === "/api/admin/budget")
      json = { limitUsd: 10, spentUsd: 2, reservedUsd: 1, enabled: true };
    else if (path === "/api/admin/announcements" && req.method() === "GET")
      json = notices;
    else if (path === "/api/admin/announcements") {
      keys.push(req.headers()["idempotency-key"]);
      if (expire) {
        expire = false;
        return route.fulfill({
          status: 403,
          json: {
            code: "ADMIN_REAUTH_REQUIRED",
            message: "관리자 비밀번호를 다시 확인해 주세요.",
          },
        });
      }
      json = {
        ...req.postDataJSON(),
        id: keys.at(-1),
        revision: 1,
        updatedAt: "2026-10-08T12:00:00Z",
      };
      notices.push(json);
    } else if (path.startsWith("/api/admin/lists/")) {
      const kind = path.split("/").at(-1),
        items =
          kind === "members"
            ? [
                {
                  id: "member-1",
                  username: "long_member",
                  nickname: "길게 입력된 테스트 회원 이름",
                  admin_role: "MEMBER",
                  blocked: false,
                  sessions: 2,
                  created_at: "2026-10-08T12:00:00Z",
                  admin_revision: 0,
                },
              ]
            : kind === "problems"
              ? [
                  {
                    id: "sum-v1",
                    catalog_title: "두 수의 합",
                    catalog_category: "구현",
                    catalog_tags: "입출력,구현",
                    ready: true,
                    admin_revision: 0,
                    time_limits_json: null,
                    resources: {
                      JAVA: { cpuSeconds: 5, wallSeconds: 10, memoryMb: 512 },
                      CPP: { cpuSeconds: 3, wallSeconds: 6, memoryMb: 256 },
                      PYTHON: { cpuSeconds: 8, wallSeconds: 16, memoryMb: 256 },
                    },
                  },
                ]
              : [];
      json = {
        items,
        total: items.length,
        page: Number(url.searchParams.get("page") || 0),
        size: Number(url.searchParams.get("size") || 25),
      };
    } else if (path === "/api/admin/availability")
      json = {
        banks: [
          {
            id: "exam-a-v1",
            reviewed: true,
            admin_enabled: true,
            admin_revision: 0,
            problems: 8,
            active: 2,
          },
        ],
        courses: [],
        definitions: [],
      };
    else if (path === "/api/admin/settings")
      json = { maintenance: false, message: "", revision: 0 };
    return route.fulfill({ json });
  });
  return keys;
}
test("reauthentication preserves an unsaved notice and reuses its request key", async ({
  page,
}) => {
  const keys = await fixture(page, { expireNotice: true });
  await page.goto(base);
  await page
    .getByRole("button", { name: "공지·업데이트", exact: true })
    .click();
  await page.getByRole("button", { name: "새 공지", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "새 공지", exact: true });
  await dialog
    .getByLabel("제목", { exact: true })
    .fill("<script>window.pwned=true</script>");
  await dialog.getByLabel("목록 요약").fill("서비스 소식");
  await dialog.getByLabel("본문", { exact: true }).fill("첫 문단\n\n둘째 문단");
  await dialog.getByLabel("회원에게 게시").check();
  await dialog.getByRole("button", { name: "저장·게시", exact: true }).click();
  const verify = page.getByRole("dialog", {
    name: "관리자 재인증",
    exact: true,
  });
  await expect(verify).toBeVisible();
  await verify.getByLabel("비밀번호", { exact: true }).fill("fixture-password");
  await verify
    .getByRole("button", { name: "관리자 재인증", exact: true })
    .click();
  await expect(verify).not.toBeVisible();
  await expect(
    dialog.getByRole("textbox", { name: "본문", exact: true }),
  ).toHaveValue("첫 문단\n\n둘째 문단");
  await dialog.getByRole("button", { name: "저장·게시", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "공지 편집", exact: true }),
  ).toContainText("공지를 게시했어요.");
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(await page.evaluate(() => window.pwned)).toBeUndefined();
});
test("unverified login enters the password screen before exposing management data", async ({
  page,
}) => {
  await fixture(page, { verified: false });
  await page.goto(base);
  await expect(
    page.getByRole("heading", { name: "관리자 재인증" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "운영 현황" }),
  ).not.toBeVisible();
  await page.getByLabel("비밀번호", { exact: true }).fill("fixture-password");
  await page
    .getByRole("button", { name: "관리자 재인증", exact: true })
    .click();
  await expect(page.getByRole("heading", { name: "운영 현황" })).toBeVisible();
});
for (const width of [390, 768, 1440])
  test("tables and editors fit " + width, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({
      colorScheme: width === 768 ? "dark" : "light",
      reducedMotion: "reduce",
    });
    await fixture(page);
    await page.goto(base);
    for (const name of [
      "회원·권한",
      "문제은행",
      "진단·코스 제공",
      "맞춤 계획",
      "작업 관리",
      "운영 설정",
      "감사 이력",
    ]) {
      await page
        .getByRole("navigation", { name: "운영 관리" })
        .getByRole("button", { name, exact: true })
        .click();
      await expect(
        page.getByRole("heading", {
          name: name === "맞춤 계획" ? "맞춤 훈련 계획" : name,
          exact: true,
        }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
    }
    await page.getByRole("button", { name: "회원·권한", exact: true }).click();
    await page.getByRole("button", { name: "차단", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "회원 차단", exact: true });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("변경 사유").fill("이상 접근 차단");
    await page.screenshot({
      path: "/tmp/admin-console-admin-" + width + ".png",
      fullPage: true,
    });
    const box = await dialog.boundingBox();
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    await dialog.press("Escape");
    await expect(dialog).not.toBeVisible();
  });
test("course validation failures preserve the editor input", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/admin/courses", (route) =>
    route.fulfill({
      status: 400,
      json: { message: "공개·검증 완료 문제를 선택해 주세요." },
    }),
  );
  await page.goto(base);
  await page
    .getByRole("button", { name: "진단·코스 제공", exact: true })
    .click();
  await page.getByRole("button", { name: "새 훈련 코스", exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: "새 훈련 코스",
    exact: true,
  });
  await dialog.getByLabel("코스 ID").fill("new-course");
  await dialog.getByLabel("제목", { exact: true }).fill("새 알고리즘 훈련");
  await dialog.getByLabel("코스 소개").fill("자료구조 연습");
  await dialog.getByLabel("단계 이름").fill("기초");
  await dialog.getByLabel("학습 목표").fill("스택 구현");
  await dialog.getByLabel("문제 ID · 줄마다 하나씩").fill("missing-problem");
  await dialog.getByLabel("변경 사유").fill("코스 추가 테스트");
  await dialog.getByRole("button", { name: "코스 저장" }).click();
  await expect(dialog.getByRole("alert")).toContainText("공개·검증 완료");
  await expect(dialog.getByLabel("제목", { exact: true })).toHaveValue(
    "새 알고리즘 훈련",
  );
});
