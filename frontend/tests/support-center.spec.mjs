import { test, expect } from "@playwright/test";
import { fileURLToPath } from "node:url";
const base = process.env.GAMJAOJ_BASE_URL || "http://127.0.0.1:18790";
async function fixture(
  page,
  { signedIn = true, fail = false, admin = false } = {},
) {
  const items = [],
    keys = [];
  let failed = false;
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      u = new URL(req.url()),
      p = u.pathname;
    let json = [];
    if (p === "/api/me") {
      if (!signedIn) return route.fulfill({ status: 401, json: {} });
      json = { id: "support-fixture", nickname: "회원" };
    } else if (p === "/api/auth/csrf")
      json = { headerName: "X-CSRF-TOKEN", token: "fixture" };
    else if (p === "/api/admin/me")
      json = { username: "operator", verified: true };
    else if (p === "/api/admin/overview")
      json = {
        members: 0,
        problems: 0,
        judgeQueue: {},
        generationJobs: {},
        aiTasks: {},
      };
    else if (p === "/api/admin/budget") json = { enabled: false };
    else if (p === "/api/support" && req.method() === "POST") {
      keys.push(req.headers()["idempotency-key"]);
      if (fail && !failed) {
        failed = true;
        return route.fulfill({
          status: 503,
          json: { message: "잠시 후 다시 보내 주세요." },
        });
      }
      json = {
        ...req.postDataJSON(),
        id: keys.at(-1),
        username: "회원",
        status: "OPEN",
        reply: "",
        revision: 0,
        createdAt: "2026-10-09T00:00:00Z",
      };
      items.unshift(json);
    } else if (p === "/api/support" || p === "/api/admin/support")
      json = {
        items: admin
          ? [
              {
                id: "sample",
                username: "회원",
                kind: "오류 제보",
                title: "실행 문의",
                body: "<script>window.hacked=true</script>",
                status: "OPEN",
                reply: "",
                revision: 0,
                createdAt: "2026-10-09T00:00:00Z",
              },
            ]
          : items,
        page: 0,
        hasNext: false,
      };
    else if (p === "/api/admin/support/sample")
      json = { ...req.postDataJSON() };
    return route.fulfill({ json });
  });
  await page.goto(base + (admin ? "/controloj.html" : ""));
  return { keys, items };
}
for (const width of [390, 1440])
  test(
    "member report survives failure and displays history " + width,
    async ({ page }) => {
      await page.setViewportSize({ width, height: 850 });
      await page.emulateMedia({
        reducedMotion: "reduce",
        colorScheme: width === 390 ? "dark" : "light",
      });
      const { keys } = await fixture(page, { fail: true });
      await page
        .getByRole("button", { name: "문의·제보", exact: true })
        .click();
      const dialog = page.getByRole("dialog", {
        name: "운영자 문의",
        exact: true,
      });
      await dialog
        .getByLabel("제목", { exact: true })
        .fill("실행 결과가 이상해요");
      await dialog
        .getByLabel("내용", { exact: true })
        .fill("재현 방법과 문제 이름");
      await dialog
        .getByRole("button", { name: "문의 보내기", exact: true })
        .last()
        .click();
      await expect(dialog.getByRole("alert")).toContainText("다시 보내");
      await expect(dialog.getByLabel("내용", { exact: true })).toHaveValue(
        "재현 방법과 문제 이름",
      );
      await dialog
        .getByRole("button", { name: "문의 보내기", exact: true })
        .last()
        .click();
      await expect(dialog.getByRole("status")).toContainText("접수됐어요");
      expect(keys[0]).toBe(keys[1]);
      await dialog
        .getByRole("button", { name: "내 문의", exact: true })
        .click();
      await dialog.getByText("실행 결과가 이상해요", { exact: true }).click();
      await expect(
        dialog.getByText("재현 방법과 문제 이름", { exact: true }),
      ).toBeVisible();
      await expect(dialog.getByText(/접수됨/)).toBeVisible();
      const box = await dialog.boundingBox();
      expect(box.x).toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width);
      await page.screenshot({ path: "/tmp/gamja-support-" + width + ".png" });
    },
  );
test("notice contact opens login guidance for guests", async ({ page }) => {
  await fixture(page, { signedIn: false });
  await page.getByRole("button", { name: /공지·업데이트/ }).click();
  await page
    .getByRole("button", { name: "운영자에게 문의·오류 제보하기" })
    .click();
  await expect(
    page.getByRole("dialog", { name: "공지·업데이트", exact: true }),
  ).not.toBeVisible();
  const dialog = page.getByRole("dialog", { name: "운영자 문의", exact: true });
  await expect(dialog).toContainText("로그인 후");
  await expect(dialog.locator("form")).toHaveCount(0);
});
test("administrator can read plain text and reply", async ({ page }) => {
  await fixture(page, { admin: true });
  await page
    .getByRole("button", { name: "문의·오류 제보", exact: true })
    .click();
  await page.getByRole("button", { name: "확인·답변", exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: "문의 확인·답변",
    exact: true,
  });
  await expect(
    dialog.getByText("<script>window.hacked=true</script>", { exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => window.hacked)).toBeUndefined();
  await dialog.getByLabel("운영자 답변").fill("확인해서 수정했어요.");
  await dialog
    .getByLabel("처리 상태", { exact: true })
    .selectOption("RESOLVED");
  await dialog.getByRole("button", { name: "답변·상태 저장" }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("status")).toContainText("회원의 내 문의");
});

test("support remains available before appearance initialization", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/appearance.js", (route) => route.abort());
  await fixture(page);
  await page.getByRole("button", { name: "문의·제보", exact: true }).click();
  await expect(
    page.getByRole("dialog", { name: "운영자 문의", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("dialog", { name: "운영자 문의", exact: true })
    .getByRole("button", { name: "닫기" })
    .click();
  await page.addScriptTag({
    path: fileURLToPath(new URL("../public/appearance.js", import.meta.url)),
  });
  await page.getByRole("button", { name: "화면 설정", exact: true }).click();
  await expect(
    page
      .getByRole("dialog", { name: "화면 설정", exact: true })
      .getByRole("navigation", { name: "설정 영역" }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
