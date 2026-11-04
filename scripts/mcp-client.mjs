import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { setTimeout as delay } from "node:timers/promises";

export function mcpEndpoint(value) {
  const url = new URL(value);
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/mcp" ||
      !(url.protocol === "https:" || (url.protocol === "http:" && loopback))) {
    throw new Error("Use an HTTPS /mcp endpoint or a loopback HTTP /mcp endpoint, without URL credentials.");
  }
  return url;
}

/** Official SDK connection. Diagnostics retain protocol metadata, never credentials or tool inputs. */
export async function connectWorkbench({ url, token, onExchange = () => {}, fetchImpl = fetch }) {
  const endpoint = mcpEndpoint(url);
  if (typeof token !== "string" || !token.trim()) throw new Error("A proposal-only agent token is required.");
  const client = new Client(
    { name: "x-cloud-workbench-client", version: "1.0.0" },
    { versionNegotiation: { mode: "legacy" } },
  );
  const transport = new StreamableHTTPClientTransport(endpoint, {
    authProvider: { token: async () => token.trim() },
    fetch: async (input, init = {}) => {
      const target = new URL(input instanceof Request ? input.url : String(input));
      if (target.href !== endpoint.href) throw new Error("The MCP client cannot forward credentials to another endpoint.");
      let message;
      if (typeof init.body === "string") {
        try { message = JSON.parse(init.body); } catch { /* The server rejects non-JSON bodies. */ }
      }
      const response = await fetchImpl(input, {
        ...init,
        redirect: "error",
        signal: init.signal
          ? AbortSignal.any([init.signal, AbortSignal.timeout(35000)])
          : AbortSignal.timeout(35000),
      });
      const exchange = {
        method: message?.method || init.method || "GET",
        tool: message?.method === "tools/call" ? message.params?.name : undefined,
        protocolVersion: new Headers(init.headers).get("mcp-protocol-version"),
        status: response.status,
      };
      if (message?.method === "initialize" && response.ok) {
        exchange.negotiatedVersion = (await response.clone().json()).result?.protocolVersion;
      }
      onExchange(exchange);
      return response;
    },
  });
  try {
    await client.connect(transport);
  } catch (error) {
    await client.close().catch(() => {});
    throw error;
  }
  return {
    client,
    transport,
    close: async () => {
      try { await transport.terminateSession(); } finally { await client.close(); }
    },
  };
}

export async function callStructuredTool(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) {
    const detail = result.content?.find(item => item.type === "text")?.text || "Tool failed";
    throw new Error(`${name}: ${detail.slice(0, 300)}`);
  }
  if (!result.structuredContent || typeof result.structuredContent !== "object") {
    throw new Error(`${name} did not return structured evidence.`);
  }
  return result.structuredContent;
}

/** Observe a server-bounded repair. Completion never grants approval or changes its Git head. */
export async function repairForReview(client, request, { onProgress = () => {} } = {}) {
  let run = await callStructuredTool(client, "start_repair", request);
  onProgress(run);
  const deadline = Date.now() + request.timeoutSeconds * 1000 + 20000;
  while (["QUEUED", "RUNNING", "APPLYING"].includes(run.status) && Date.now() < deadline) {
    await delay(500);
    run = await callStructuredTool(client, "inspect_repair", { id: run.id });
    onProgress(run);
  }
  return run;
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      help: { type: "boolean", short: "h" },
      "repository-id": { type: "string" },
      "run-id": { type: "string" },
      instruction: { type: "string" },
      provider: { type: "string", default: "mutation-search" },
      attempts: { type: "string", default: "8" },
      seconds: { type: "string", default: "90" },
      output: { type: "string", default: "target/mcp-demo.json" },
    },
  });
  if (values.help) {
    console.log("Usage: npm run mcp:inspect [-- --run-id ID]\n       npm run mcp:repair -- --repository-id ID --instruction TEXT [--provider mutation-search|gemini|nebius] [--attempts 8] [--seconds 90] [--output target/mcp-demo.json]\nSet MCP_URL and PLATFORM_AGENT_TOKEN privately. Loopback connections may read the local agent.token file. No approval or commit action is offered.");
    return;
  }
  const command = positionals[0] || "inspect";
  if (positionals.length > 1 || !["inspect", "repair"].includes(command)) throw new Error("Choose inspect or repair; use --help for usage.");
  const url = mcpEndpoint(process.env.MCP_URL || "http://127.0.0.1:8080/mcp");
  let token = process.env.PLATFORM_AGENT_TOKEN;
  if (!token && ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
    token = await readFile(resolve(process.env.PLATFORM_DATA_DIR || ".workbench", "agent.token"), "utf8");
  }
  const maxAttempts = Number(values.attempts), timeoutSeconds = Number(values.seconds);
  if (command === "repair" && (!values["repository-id"] || !values.instruction ||
      !["mutation-search", "gemini", "nebius"].includes(values.provider) ||
      !Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > (values.provider === "mutation-search" ? 12 : 3) ||
      !Number.isInteger(timeoutSeconds) || timeoutSeconds < 20 || timeoutSeconds > 120)) {
    throw new Error("Repair requires a repository, instruction, valid provider, 1–12 attempts (live models: 1–3), and 20–120 seconds.");
  }
  const exchanges = [];
  const connection = await connectWorkbench({ url: url.href, token, onExchange: e => exchanges.push(e) });
  try {
    const { tools } = await connection.client.listTools();
    if (command === "inspect") {
      if (values["run-id"]) {
        console.log(JSON.stringify(await callStructuredTool(connection.client, "inspect_repair", { id: values["run-id"] }), null, 2));
        return;
      }
      if (!tools.some(tool => tool.name === "list_repositories")) {
        throw new Error("The connected release has no list_repositories tool. Deploy the MCP client release, or inspect a known run with --run-id.");
      }
      const { repositories } = await callStructuredTool(connection.client, "list_repositories");
      console.log(JSON.stringify({ server: connection.client.getServerVersion(), repositories, tools }, null, 2));
      return;
    }
    let previous;
    const run = await repairForReview(connection.client, {
      repositoryId: values["repository-id"], instruction: values.instruction,
      provider: values.provider, maxAttempts, timeoutSeconds,
    }, { onProgress: r => {
      if (r.status !== previous) console.log(`${r.id} · ${r.status}`);
      previous = r.status;
    } });
    const report = {
      recordedAt: new Date().toISOString(), endpoint: url.href,
      client: "@modelcontextprotocol/client", server: connection.client.getServerVersion(),
      protocolVersion: exchanges.find(e => e.method === "initialize")?.negotiatedVersion,
      run, exchanges,
      boundary: "No patch approval or commit was performed. Mutation search is a deterministic reference mode; live provider results require private model access.",
    };
    await mkdir(dirname(resolve(values.output)), { recursive: true });
    await writeFile(values.output, JSON.stringify(report, null, 2) + "\n");
    console.log(`Evidence: ${values.output}\nReview in ${url.origin}/#code · select run ${run.id}`);
    if (run.status !== "AWAITING_REVIEW") throw new Error(`Repair ended as ${run.status}; actual evidence was saved. The CLI observation deadline does not cancel a queued server job.`);
  } finally {
    await connection.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(error instanceof Error ? error.message : "MCP client failed.");
    process.exitCode = 1;
  });
}
