const { test, expect } = require("@playwright/test");
const fs = require("node:fs");

test.describe.configure({ mode: "serial" });
let errors = [];
test.beforeEach(async ({ page }) => {
  errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
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
  if (process.env.CAPTURE_DEMO) {
    fs.mkdirSync("docs/images", { recursive: true });
    await page.screenshot({
      path: "docs/images/workbench.png",
      fullPage: true,
    });
  }
  await approve(page);
  const environment = page
    .locator(".environment-card")
    .filter({
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
  await expect(page.getByText("22 / 22", { exact: true })).toBeVisible();
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
