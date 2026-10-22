const { test, expect } = require("@playwright/test");
const fs = require("node:fs");

test.describe.configure({ mode: "serial" });
let errors = [];
test.beforeEach(async ({ page }) => {
  errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await page
    .getByLabel("Access token", { exact: true })
    .fill("e2e-operator-token-0123456789");
  await page.getByRole("button", { name: "Connect to workspace" }).click();
  await expect(
    page.getByText("Control plane connected", { exact: true }),
  ).toBeVisible();
});
test.afterEach(() => expect(errors).toEqual([]));

async function compile(page, example) {
  if (example)
    await page.getByRole("button", { name: example, exact: true }).click();
  await page.getByRole("button", { name: "Compile intent" }).click();
}

async function approve(page) {
  await page.getByRole("button", { name: /Review & approve/ }).click();
  await expect(page.locator("#approval-confirm")).toBeDisabled();
  await page
    .getByLabel(
      "I reviewed this plan and approve its execution in the local sandbox.",
    )
    .check();
  await page
    .getByRole("button", { name: "Approve & execute", exact: true })
    .click();
  await expect(page).toHaveURL(/#environments$/);
}

test("review, approve, drift and reconcile through the real UI", async ({
  page,
}) => {
  await compile(page);
  await expect(page.locator("#plan-status")).toHaveText("Policy checks passed");
  await page.getByRole("tab", { name: /Artifacts/ }).click();
  await page.locator("#artifact-select").selectOption({ label: "Main.java" });
  await expect(page.locator("#artifact-code")).toContainText(
    "public class Main",
  );
  await page.getByRole("tab", { name: "Intent", exact: true }).click();
  await page.evaluate(() => window.scrollTo(0, 0));
  fs.mkdirSync("target/screenshots", { recursive: true });
  await page.screenshot({
    path: "target/screenshots/workbench.png",
    fullPage: true,
  });
  if (process.env.CAPTURE_DEMO) {
    fs.mkdirSync("docs/images", { recursive: true });
    await page.screenshot({
      path: "docs/images/workbench.png",
      fullPage: true,
    });
  }
  await approve(page);
  const environment = page.locator(".environment-card").filter({
    has: page.getByRole("heading", {
      name: "payments-api-production",
      exact: true,
    }),
  });
  await expect(
    environment.getByText("Converged", { exact: true }),
  ).toBeVisible();
  await environment
    .getByRole("button", { name: "Lose replicas", exact: true })
    .click();
  await expect(
    environment.getByText("1 drifted field", { exact: true }),
  ).toBeVisible();
  await environment.getByRole("button", { name: "Plan recovery" }).click();
  await expect(page).toHaveURL(/#workbench$/);
  await expect(page.locator(".proposal-tab")).toContainText(
    "RESTORE / replicas",
  );
  await approve(page);
  await expect(
    environment.getByText("Converged", { exact: true }),
  ).toBeVisible();
  await expect(environment.locator(".environment-meta")).toContainText(
    "Desired version 1",
  );
});

test("unsafe requests are visibly denied and cannot be approved", async ({
  page,
}) => {
  await compile(page, "Unsafe production networking");
  await expect(page.locator("#plan-status")).toHaveText("Policy denied");
  await expect(
    page.getByRole("button", { name: /Execution blocked/ }),
  ).toBeDisabled();
  await expect(page.locator(".proposal-tab")).toContainText("PRIVATE_NETWORK");
  await expect(page.locator(".proposal-tab")).toContainText(
    "RESTRICTED_EGRESS",
  );
});

test("ambiguous requests ask for clarification with no executable proposal", async ({
  page,
}) => {
  await compile(page, "Missing requirements");
  await expect(
    page.getByRole("heading", { name: "No executable proposal" }),
  ).toBeVisible();
  await expect(page.locator("#feedback-section")).toBeHidden();
  await expect(
    page.getByRole("button", { name: /Review & approve/ }),
  ).toHaveCount(0);
});

test("reviewed feedback is included in the next evaluation", async ({
  page,
}) => {
  await compile(page, "Production service");
  await expect(page.locator("#plan-status")).toHaveText("Policy checks passed");
  await page.getByText("Close the feedback loop", { exact: true }).click();
  await page
    .locator("#feedback-note")
    .fill("Preserve production PII requirements through the full lifecycle.");
  await page
    .getByRole("button", { name: /Save regression & run suite/ })
    .click();
  await expect(page).toHaveURL(/#evaluations$/);
  await expect(page.getByText("23 / 23", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("cell", {
      name: "Preserve production PII requirements through the full lifecycle.",
    }),
  ).toBeVisible();
  await expect(page.getByText("100%", { exact: true })).toBeVisible();
});

test("mobile layout stays within the viewport and keeps navigation usable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  await page.getByRole("link", { name: "Evaluations", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Make behavior measurable." }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
});

test("repository workflow creates a real task snapshot and discloses runner availability", async ({
  page,
}) => {
  await page
    .getByRole("link", { name: "Repository agent", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "From regression to reviewed repair." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Create task repository" }).click();
  await expect(page.locator("#code-repository")).not.toHaveValue("");
  await expect(page.locator("#code-source")).toContainText("class Rules");
  await page.getByRole("button", { name: /RulesTest.java/ }).click();
  await expect(page.locator("#code-file-access")).toHaveText("Immutable input");
  await expect(page.locator("#code-source")).toContainText("AssertionError");
  fs.mkdirSync("target/screenshots", { recursive: true });
  await page.screenshot({ path: "target/screenshots/repository-workspace.png", fullPage: true });
  await expect(page.getByLabel("Repair request")).toHaveValue(
    /Fix the discount/,
  );
  await expect(page.locator("#code-notice")).toContainText(
    /Isolated runner ready|Docker runner unavailable/,
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test("invalid credentials cannot unlock the workspace", async ({ page }) => {
  await page.evaluate(() => sessionStorage.clear());
  await page.reload();
  await page
    .getByLabel("Access token", { exact: true })
    .fill("invalid-token-with-enough-characters");
  await page.getByRole("button", { name: "Connect to workspace" }).click();
  await expect(page.locator("#login-error")).toContainText("bearer token");
  await expect(page.locator("#login-dialog")).toBeVisible();
});

test("isolated repair, patch approval and evaluated promotion through the UI", async ({
  page,
  request,
}) => {
  test.setTimeout(150000);
  const response = await request.get("/api/code/config", {
    headers: { Authorization: "Bearer e2e-operator-token-0123456789" },
  });
  test.skip(
    !(await response.json()).sandbox.available,
    "Requires the isolated Docker runner; verified in CI.",
  );
  await page
    .getByRole("link", { name: "Repository agent", exact: true })
    .click();
  await page.getByRole("button", { name: "Create task repository" }).click();
  await expect(page.locator("#code-repository")).not.toHaveValue("");
  await page.getByRole("button", { name: "Run isolated repair" }).click();
  await expect(
    page.getByRole("button", { name: "Review & commit patch" }),
  ).toBeEnabled({ timeout: 120000 });
  await expect(page.locator(".patch-output")).toContainText("amount - 5");
  await expect(page.locator("#code-result")).toContainText("FAIL → PASS");
  await page.evaluate(() => window.scrollTo(0, 0));
  fs.mkdirSync("target/screenshots", { recursive: true });
  await page.screenshot({
    path: "target/screenshots/repository-repair.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Review & commit patch" }).click();
  await expect(
    page.getByRole("button", { name: "Commit reviewed patch" }),
  ).toBeDisabled();
  await page
    .getByLabel("I reviewed the source diff and unchanged test results.")
    .check();
  await page.getByRole("button", { name: "Commit reviewed patch" }).click();
  await expect(page.locator("#code-result")).toContainText(
    "Reviewed patch committed",
  );
  await page
    .getByRole("button", { name: "Compare feedback candidate" })
    .click();
  await expect(
    page.getByRole("button", { name: "Promote evaluated candidate" }),
  ).toBeVisible({ timeout: 120000 });
  await expect(page.locator("#code-comparisons")).toContainText(
    "container-isolation: PASS",
  );
  await page
    .getByRole("button", { name: "Promote evaluated candidate" })
    .click();
  await expect(page.locator("#toast")).toHaveText(
    "Evaluated search policy promoted.",
  );
});
