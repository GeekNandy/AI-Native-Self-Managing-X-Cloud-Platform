const { test, expect } = require("@playwright/test");
const { default: AxeBuilder } = require("@axe-core/playwright");
const fs = require("node:fs");
const { spawn } = require("node:child_process");
const token = "e2e-operator-token-0123456789";
const views = ["workbench", "code", "environments", "evaluations", "activity"];
async function login(page, value = token) {
  await page.getByLabel("Access token", { exact: true }).fill(value);
  await page.getByRole("button", { name: "Connect to workspace" }).click();
  await expect(
    page.getByText("Control plane connected", { exact: true }),
  ).toBeVisible();
}
async function compile(page) {
  await page.getByRole("button", { name: "Compile intent" }).click();
  await expect(page.locator("#plan-status")).toHaveText("Policy checks passed");
}
async function shot(page, name, fullPage = true) {
  fs.mkdirSync("target/screenshots", { recursive: true });
  await page.evaluate(() => scrollTo(0, 0));
  await page.screenshot({
    path: `target/screenshots/${name}.png`,
    fullPage,
    animations: "disabled",
  });
}
let errors;
test.beforeEach(async ({ page }) => {
  errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await login(page);
});
test.afterEach(() => expect(errors).toEqual([]));

test("keyboard page navigation and review tabs preserve focus", async ({
  page,
}) => {
  await page.keyboard.press("Control+k");
  await expect(
    page.getByRole("dialog", { name: "Navigate workspace" }),
  ).toBeVisible();
  await page
    .getByRole("searchbox", { name: "Navigate workspace" })
    .fill("state");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/#environments$/);
  await expect(page.locator("#view-environments h1")).toBeFocused();
  await page
    .getByRole("link", { name: "Agent workbench", exact: true })
    .click();
  await compile(page);
  await page.getByRole("tab", { name: "Intent", exact: true }).focus();
  await page.keyboard.press("ArrowRight");
  await expect(page.getByRole("tab", { name: /Policy/ })).toBeFocused();
  await expect(page.locator(".proposal-tab")).toContainText("PRIVATE_NETWORK");
  await page.keyboard.press("End");
  await expect(page.getByRole("tab", { name: /Artifacts/ })).toBeFocused();
  await page.locator(".skip-link").focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("main")).toBeFocused();
  await expect(page).toHaveURL(/#workbench$/);
});

test("themes persist and runtime assets remain local under the existing CSP", async ({
  page,
}) => {
  const external = [],
    consoleErrors = [];
  page.on("request", (r) => {
    if (!r.url().startsWith("http://127.0.0.1:8090/")) external.push(r.url());
  });
  page.on("console", (m) => {
    if (m.type() === "error") consoleErrors.push(m.text());
  });
  const response = await page.reload();
  expect(response.headers()["content-security-policy"]).toContain(
    "script-src 'self'; style-src 'self'",
  );
  await expect(
    page.getByText("Control plane connected", { exact: true }),
  ).toBeVisible();
  await compile(page);
  await shot(page, "workbench-light");
  await page.getByRole("button", { name: "Switch to dark theme" }).click();
  await shot(page, "workbench-dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "Switch to light theme" }).click();
  expect(external).toEqual([]);
  expect(consoleErrors).toEqual([]);
});

test("recorded environments, evaluation history, audit events and exports can be filtered", async ({
  page,
}) => {
  await page.getByRole("link", { name: "Environments", exact: true }).click();
  await page
    .getByRole("searchbox", { name: "Find an environment" })
    .fill("impossible-environment");
  await expect(page.locator("#environment-count")).toContainText("0 of");
  await page.getByRole("searchbox", { name: "Find an environment" }).fill("");
  await shot(page, "environments");
  await page.getByRole("link", { name: "Evaluations", exact: true }).click();
  await page.getByRole("button", { name: "Run control-plane suite" }).click();
  await expect(page.locator("#evaluation-content")).toContainText(
    "Evaluation results",
  );
  await expect(
    page.getByRole("button", { name: "Run control-plane suite" }),
  ).toBeEnabled();
  const id = await page.getByLabel("Evaluation run").inputValue();
  await page.getByRole("button", { name: "Run control-plane suite" }).click();
  await expect(page.getByLabel("Evaluation run")).not.toHaveValue(id);
  await page.getByLabel("Evaluation run").selectOption(id);
  await page.getByLabel("Evaluation result").selectOption("FAIL");
  await expect(
    page.locator("#evaluation-content tbody tr:visible"),
  ).toHaveCount(0);
  await page.getByLabel("Evaluation result").selectOption("all");
  await shot(page, "evaluations");
  await page.getByRole("link", { name: "Activity log", exact: true }).click();
  await page
    .getByRole("searchbox", { name: "Search activity" })
    .fill("impossible-event");
  await expect(
    page.getByRole("button", { name: "Export events" }),
  ).toBeDisabled();
  await page
    .getByRole("searchbox", { name: "Search activity" })
    .fill("payments");
  await expect(
    page.getByRole("button", { name: "Export events" }),
  ).toBeEnabled();
  const waiting = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export events" }).click();
  const data = JSON.parse(
    fs.readFileSync(await (await waiting).path(), "utf8"),
  );
  expect(data.length).toBeGreaterThan(0);
  expect(
    data.every((e) =>
      Object.values(e).join(" ").toLowerCase().includes("payments"),
    ),
  ).toBe(true);
  await shot(page, "activity");
});

test("agent roles disable operator actions and disconnect removes the tab credential", async ({
  page,
}) => {
  await page
    .getByRole("button", { name: "Disconnect workspace", exact: true })
    .click();
  await expect(page.locator("#login-dialog")).toBeVisible();
  expect(
    await page.evaluate(() => sessionStorage.getItem("workbench-token")),
  ).toBeNull();
  await login(page, "e2e-agent-token-0123456789");
  await expect(page.locator("#role-notice")).toContainText("Agent access");
  await compile(page);
  await expect(
    page.getByRole("button", { name: /Review & approve/ }),
  ).toBeDisabled();
  await page.getByRole("link", { name: "Evaluations", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Run control-plane suite" }),
  ).toBeDisabled();
  await page
    .getByRole("link", { name: "Repository agent", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Create task repository" }),
  ).toBeDisabled();
  await expect(page.getByLabel("Import repository JSON")).toBeDisabled();
});

test("all five views fit mobile, tablet and desktop in both themes", async ({
  page,
}) => {
  test.setTimeout(90000);
  for (const theme of ["light", "dark"]) {
    if ((await page.locator("html").getAttribute("data-theme")) !== theme)
      await page.locator("#theme-toggle").click();
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: width < 760 ? 844 : 1000 });
      for (const view of views) {
        await page.goto("/#" + view);
        await expect(
          page.getByText("Control plane connected", { exact: true }),
        ).toBeVisible();
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth),
          `${view} ${theme} ${width}`,
        ).toBeLessThanOrEqual(width);
      }
    }
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#workbench");
  await expect(
    page.getByText("Control plane connected", { exact: true }),
  ).toBeVisible();
  await shot(page, "mobile-workbench-dark", false);
  await page
    .getByRole("link", { name: "Repository agent", exact: true })
    .click();
  await shot(page, "mobile-repository-dark", false);
});

test("recorded verified patch keeps line gutters and accessible colors in both themes", async ({ page }) => {
  // Rendering contract uses a real exported execution receipt; it does not run or score a model.
  const receipt = JSON.parse(fs.readFileSync("docs/evidence/hosted-remote-repair.json", "utf8")).repair;
  await page.route("**/api/code/runs", route => route.request().method() === "GET" ? route.fulfill({ json: [receipt] }) : route.continue());
  await page.route(`**/api/code/runs/${receipt.id}/model-calls`, route => route.fulfill({ json: [] }));
  await page.route(`**/api/code/runs/${receipt.id}/publication`, route => route.fulfill({ json: {status:"NOT_PUBLISHED"} }));
  await page.reload();
  await expect(page.getByText("Control plane connected", { exact:true })).toBeVisible();
  await page.getByRole("link", {name:"Repository agent",exact:true}).click();
  await expect(page.locator(".diff-view")).toHaveAttribute("data-highlighted","true");
  await expect(page.locator(".diff-row").first()).toHaveCSS("display","grid");
  await expect(page.locator(".diff-view .file")).toHaveCount(0);
  for (const theme of ["light","dark"]) {
    const current = await page.locator("html").getAttribute("data-theme");
    if ((current || "light") !== theme) await page.getByRole("button", { name: `Switch to ${theme} theme` }).click();
    const result = await new AxeBuilder({page}).include("#view-code").withTags(["wcag2a","wcag2aa","wcag21aa","wcag22aa"]).analyze();
    expect(result.violations).toEqual([]);
  }
});

test("all screens and the access dialog pass automated WCAG checks", async ({
  page,
}) => {
  test.setTimeout(120000);
  const violations = [];
  for (const theme of ["light", "dark"]) {
    if ((await page.locator("html").getAttribute("data-theme")) !== theme)
      await page.locator("#theme-toggle").click();
    for (const view of views) {
      await page.goto("/#" + view);
      await expect(
        page.getByText("Control plane connected", { exact: true }),
      ).toBeVisible();
      if (view === "workbench") await compile(page);
      if (view === "code")
        await expect(page.locator("#code-notice")).toContainText(
          /runner ready|runner unavailable/,
        );
      const result = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze();
      violations.push(
        ...result.violations.map((v) => ({
          view,
          theme,
          id: v.id,
          nodes: v.nodes.map((n) => ({
            target: n.target,
            summary: n.failureSummary,
          })),
        })),
      );
    }
  }
  await page
    .getByRole("button", { name: "Disconnect workspace", exact: true })
    .click();
  await expect(page.locator("#login-dialog")).toBeVisible();
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  violations.push(
    ...result.violations.map((v) => ({
      view: "access",
      id: v.id,
      nodes: v.nodes.map((n) => n.target),
    })),
  );
  fs.writeFileSync(
    "target/ui-accessibility.json",
    JSON.stringify({ engine: "axe-core", screens: 11, violations }, null, 2),
  );
  expect(violations).toEqual([]);
});

test("hosted username/password protects the UI and opens the workspace without a token prompt", async ({
  browser,
  request,
}) => {
  test.setTimeout(60000);
  const base = "http://127.0.0.1:8091";
  const username = "browser-reviewer",
    password = "temporary-browser-password";
  const child = spawn(
    "java",
    [
      "-jar",
      "target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar",
      "--server.port=8091",
    ],
    {
      env: {
        ...process.env,
        PLATFORM_DATA_DIR: "memory",
        PLATFORM_AI_PROVIDER: "fixture",
        PUBLIC_ORIGIN: base,
        DEMO_USERNAME: username,
        DEMO_PASSWORD: password,
        DEMO_AUTH_REQUIRED: "true",
        DEMO_EPHEMERAL: "true",
      },
      stdio: "ignore",
    },
  );
  let context;
  try {
    await expect
      .poll(
        async () => {
          try {
            return (await request.get(base + "/actuator/health")).status();
          } catch {
            return 0;
          }
        },
        { timeout: 40000 },
      )
      .toBe(200);
    for (const path of ["/", "/app.js", "/api/config", "/actuator/metrics"]) {
      const r = await request.get(base + path);
      expect(r.status()).toBe(401);
      expect(r.headers()["www-authenticate"]).toContain("Basic");
    }
    context = await browser.newContext({
      baseURL: base,
      httpCredentials: { username, password },
    });
    const page = await context.newPage();
    await page.goto("/");
    await expect(
      page.getByText("Control plane connected", { exact: true }),
    ).toBeVisible();
    await expect(page.locator("#login-dialog")).toBeHidden();
    await expect(page.locator("#retention-notice")).toContainText(
      "Hosted demo storage",
    );
    await compile(page);
    await shot(page, "hosted-password-workbench");
    const authorization =
      "Basic " + Buffer.from(username + ":" + password).toString("base64");
    expect(
      (
        await request.get(base + "/api/environments", {
          headers: {
            Authorization: authorization,
            Origin: "https://attacker.test",
          },
        })
      ).status(),
    ).toBe(403);
  } finally {
    if (context) await context.close();
    child.kill("SIGTERM");
    await new Promise((resolve) => {
      if (child.exitCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(() => {
        child.kill("SIGKILL");
        resolve();
      }, 5000).unref();
    });
  }
});
