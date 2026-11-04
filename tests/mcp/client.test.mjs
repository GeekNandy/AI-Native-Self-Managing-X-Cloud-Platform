import test from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync, execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
import { randomBytes, createHash } from "node:crypto";
import { once } from "node:events";
import { promisify } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import { callStructuredTool, connectWorkbench, mcpEndpoint, repairForReview } from "../../scripts/mcp-client.mjs";

test("official MCP SDK against the packaged workbench", { timeout: 180000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), "xcloud-mcp-"));
  const listener = createServer();
  listener.listen(0, "127.0.0.1");
  await once(listener, "listening");
  const port = listener.address().port;
  await new Promise(resolveClose => listener.close(resolveClose));
  const origin = `http://127.0.0.1:${port}`;
  const agent = randomBytes(32).toString("hex"), operator = randomBytes(32).toString("hex");
  const child = spawn("java", ["-Xmx256m", "-jar", resolve("target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar")], {
    stdio: "ignore",
    env: {
      ...process.env, PORT: String(port), SERVER_ADDRESS: "127.0.0.1", DATABASE_URL: "",
      PLATFORM_DATA_DIR: directory, PLATFORM_AI_PROVIDER: "fixture", PLATFORM_RUNTIME: "sandbox",
      PLATFORM_NODE_ROLE: "coordinator", PLATFORM_EXECUTION_MODE: "local",
      PLATFORM_SANDBOX_IMAGE: "xcloud-java21-runner:1", DEMO_AUTH_REQUIRED: "false",
      PUBLIC_ORIGIN: "", RENDER_EXTERNAL_URL: "", DEMO_USERNAME: "", DEMO_PASSWORD: "",
      PLATFORM_OPERATOR_TOKEN: operator, PLATFORM_AGENT_TOKEN: agent, PLATFORM_WORKER_TOKEN: "",
      GEMINI_API_KEY: "", NEBIUS_API_KEY: "", GITHUB_WORKBENCH_TOKEN: "",
    },
  });
  let launchError;
  child.on("error", error => { launchError = error; });
  const exited = () => child.exitCode !== null || child.signalCode !== null;
  const exchanges = [], checks = {};
  let connection, repair;
  const started = performance.now();
  const rest = (path, { token = operator, body } = {}) => fetch(origin + path, {
    method: body === undefined ? "GET" : "POST", redirect: "error",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000),
  });
  try {
    let ready = false;
    for (let i = 0; i < 250; i++) {
      if (launchError || exited()) throw new Error("Packaged server could not start. Check Java 21 and the packaged JAR.");
      try {
        const response = await fetch(origin + "/readyz", { signal: AbortSignal.timeout(1000) });
        if (response.ok) { ready = true; break; }
      } catch { /* Wait for real application startup. */ }
      await delay(200);
    }
    assert.ok(ready, "The packaged server reached readiness");
    await t.test("initialize, discover and return structured tool results", async () => {
      connection = await connectWorkbench({ url: origin + "/mcp", token: agent,
        onExchange: exchange => exchanges.push(exchange) });
      assert.equal(connection.client.getServerVersion().name, "x-cloud-workbench");
      assert.equal(exchanges.find(e => e.method === "initialize")?.negotiatedVersion, "2025-11-25");
      const { tools } = await connection.client.listTools();
      assert.equal(tools.length, 10);
      assert.ok(tools.every(tool => tool.inputSchema.additionalProperties === false));
      assert.ok(tools.every(tool => !/approve|apply|publish|promote/.test(tool.name)));
      assert.equal(exchanges.find(e => e.method === "tools/list")?.protocolVersion, "2025-11-25");
      assert.ok(exchanges.some(e => e.method === "notifications/initialized" && e.status === 202));
      const tasks = await callStructuredTool(connection.client, "list_repair_tasks");
      assert.ok(tasks.tasks.some(task => task.id === "feedback-discount"));
      assert.deepEqual((await callStructuredTool(connection.client, "list_repositories")).repositories, []);
      const examples = await (await rest("/api/examples")).json();
      const plan = await callStructuredTool(connection.client, "propose_intent", { spec: examples[0].spec });
      assert.equal(plan.status, "AWAITING_APPROVAL");
      checks.sdkHandshake = true;
      checks.discoveryAndStructuredResults = true;
      checks.protocolVersionNegotiated = "2025-11-25";
    });
    await t.test("invalid credentials, foreign origins and privileged tools are denied", async () => {
      const invalidExchanges = [], foreignExchanges = [];
      await assert.rejects(connectWorkbench({ url: origin + "/mcp", token: "invalid-agent-token-0123456789",
        onExchange: exchange => invalidExchanges.push(exchange) }));
      assert.ok(invalidExchanges.some(exchange => exchange.status === 401));
      await assert.rejects(connectWorkbench({ url: origin + "/mcp", token: agent,
        onExchange: exchange => foreignExchanges.push(exchange),
        fetchImpl: (input, init) => {
          const headers = new Headers(init.headers);
          headers.set("Origin", "https://attacker.invalid");
          return fetch(input, { ...init, headers });
        } }));
      assert.ok(foreignExchanges.some(exchange => exchange.status === 403));
      await assert.rejects(connection.client.callTool({ name: "approve_plan", arguments: {} }));
      await assert.rejects(connection.client.callTool({ name: "inspect_repository", arguments: { id: "missing", command: "ignored" } }));
      assert.equal((await connection.client.callTool({ name: "inspect_repository", arguments: { id: "missing" } })).isError, true);
      assert.equal((await rest("/api/feedback", { token: agent, body: {} })).status, 403);
      assert.throws(() => mcpEndpoint("https://user:secret@example.com/mcp"));
      assert.throws(() => mcpEndpoint("http://example.com/mcp"));
      checks.invalidCredentialsDenied = true;
      checks.foreignOriginDenied = true;
      checks.operatorAuthorityDenied = true;
      checks.extraToolArgumentsDenied = true;
    });
    await t.test("the demo CLI discovers tools without exposing its credential", async () => {
      const { stdout } = await promisify(execFile)(process.execPath, ["scripts/mcp-client.mjs", "inspect"], {
        env: { ...process.env, MCP_URL: origin + "/mcp", PLATFORM_AGENT_TOKEN: agent },
        timeout: 15000, maxBuffer: 262144,
      });
      const result = JSON.parse(stdout);
      assert.equal(result.server.name, "x-cloud-workbench");
      assert.equal(result.tools.length, 10);
      assert.deepEqual(result.repositories, []);
      assert.ok(!stdout.includes(agent) && !stdout.includes(operator));
      checks.reproducibleCli = true;
    });
    const available = (await (await rest("/api/code/config")).json()).sandbox.available;
    await t.test("MCP starts a real Docker repair and stops for operator review", {
      skip: !available && process.env.MCP_REQUIRE_DOCKER !== "true",
    }, async () => {
      assert.ok(available, "CI requires the preloaded Docker runner; no simulated repair fallback");
      const repository = await (await rest("/api/code/repositories/catalog", { body: { taskId: "feedback-discount" } })).json();
      repair = await repairForReview(connection.client, {
        repositoryId: repository.id,
        instruction: "Fix the discount calculation while preserving rejection of negative amounts. Keep tests unchanged.",
        provider: "mutation-search", maxAttempts: 8, timeoutSeconds: 90,
      });
      assert.equal(repair.status, "AWAITING_REVIEW");
      assert.equal(repair.baseline.status, "FAIL");
      assert.equal(repair.verification.status, "PASS");
      assert.equal(repair.approvedCommit, null);
      const current = await callStructuredTool(connection.client, "inspect_repository", { id: repository.id });
      assert.equal(current.head, repository.head);
      assert.equal((await rest(`/api/code/runs/${repair.id}/approve`, { token: agent, body: { digest: repair.digest } })).status, 403);
      checks.realDockerRepair = true;
      checks.failingBaselineAndPassingVerification = true;
      checks.headUnchangedUntilReview = true;
      checks.agentCannotCommitPatch = true;
    });
  } finally {
    if (connection) await connection.close().catch(() => {});
    if (!exited() && !launchError) {
      const exit = once(child, "exit");
      child.kill("SIGTERM");
      await Promise.race([exit, delay(15000, undefined, { ref: false })]);
      if (!exited()) { child.kill("SIGKILL"); await exit; }
    }
    await mkdir("target", { recursive: true });
    const sdk = JSON.parse(await readFile("node_modules/@modelcontextprotocol/client/package.json", "utf8"));
    const paths = spawnSync("git", ["ls-files", "-z", "--", "src/main", "pom.xml", "package.json", "package-lock.json"], { encoding: "utf8" }).stdout.split("\0").filter(Boolean);
    paths.push("scripts/mcp-client.mjs", "tests/mcp/client.test.mjs");
    const source = createHash("sha256");
    for (const path of [...new Set(paths)].sort()) { source.update(path + "\0"); source.update(await readFile(path)); }
    const report = {
      recordedAt: new Date().toISOString(), sdk: { package: sdk.name, version: sdk.version },
      revision: spawnSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).stdout.trim(),
      sourceTreeDirty: Boolean(spawnSync("git", ["status", "--porcelain"], { encoding: "utf8" }).stdout.trim()),
      sourceDigest: source.digest("hex"), dockerRequired: process.env.MCP_REQUIRE_DOCKER === "true",
      checks, repair: repair ? { id: repair.id, provider: repair.provider, status: repair.status,
        baseline: repair.baseline?.status, verification: repair.verification?.status,
        digest: repair.digest, approvedCommit: repair.approvedCommit } : null,
      exchanges, elapsedMs: Math.round(performance.now() - started),
      limitation: "SDK interoperability and bounded execution evidence. Mutation search is a deterministic reference mode; no live-model quality is measured. Browser operator review is verified separately.",
    };
    await writeFile("target/mcp-sdk-evidence.json", JSON.stringify(report, null, 2) + "\n");
    await rm(directory, { recursive: true, force: true });
  }
});
