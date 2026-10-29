const { test, expect } = require("@playwright/test");
const fs = require("node:fs");

test("MCP agent repair requires an explicit operator review in the UI", async ({ page, request }) => {
  test.setTimeout(150000);
  const headers = { Authorization: "Bearer e2e-operator-token-0123456789" };
  const config = await (await request.get("/api/code/config", { headers })).json();
  test.skip(!config.sandbox.available, "Requires the real Docker runner; CI runs this scenario.");
  const repository = await (await request.post("/api/code/repositories/catalog", {
    headers, data: { taskId: "feedback-discount" },
  })).json();
  const { connectWorkbench, repairForReview, callStructuredTool } = await import("../../scripts/mcp-client.mjs");
  const connection = await connectWorkbench({
    url: "http://127.0.0.1:8090/mcp", token: "e2e-agent-token-0123456789",
  });
  try {
    const run = await repairForReview(connection.client, {
      repositoryId: repository.id,
      instruction: "Fix the discount calculation. Preserve negative input rejection and unchanged tests.",
      provider: "mutation-search", maxAttempts: 8, timeoutSeconds: 90,
    });
    expect(run.status).toBe("AWAITING_REVIEW");
    expect(run.baseline.status).toBe("FAIL");
    expect(run.verification.status).toBe("PASS");
    expect(run.approvedCommit).toBeNull();
    const before = await callStructuredTool(connection.client, "inspect_repository", { id: repository.id });
    expect(before.head).toBe(repository.head);
    await page.goto("/");
    await page.getByLabel("Access token", { exact: true }).fill("e2e-operator-token-0123456789");
    await page.getByRole("button", { name: "Connect to workspace" }).click();
    await expect(page.getByText("Control plane connected", { exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Repository agent", exact: true }).click();
    await page.locator(`[data-run="${run.id}"]`).click();
    await expect(page.locator("#code-result")).toContainText("FAIL → PASS");
    await page.getByRole("button", { name: "Review & commit patch" }).click();
    const commit = page.getByRole("button", { name: "Commit reviewed patch" });
    await expect(commit).toBeDisabled();
    await page.getByLabel("I reviewed the source diff and unchanged test results.").check();
    await commit.click();
    await expect(page.locator("#code-result")).toContainText("Reviewed patch committed");
    const reviewed = await callStructuredTool(connection.client, "inspect_repair", { id: run.id });
    expect(reviewed.status).toBe("APPROVED");
    expect(reviewed.approvedCommit).not.toBe(repository.head);
    fs.mkdirSync("target", { recursive: true });
    fs.writeFileSync("target/mcp-review-evidence.json", JSON.stringify({
      client: "Official MCP TypeScript SDK", provider: run.provider, runId: run.id,
      baseline: run.baseline.status, verification: run.verification.status,
      headUnchangedBeforeReview: before.head === repository.head,
      operatorConfirmationRequired: true, statusAfterUiReview: reviewed.status,
      approvedCommit: reviewed.approvedCommit,
      limitation: "Actual Docker reference-search repair followed by an automated browser operator review; no live-model quality claim.",
    }, null, 2) + "\n");
  } finally {
    await connection.close();
  }
});
