# MCP connection

The local server exposes `/mcp` using stateless Streamable HTTP, pinned to **2025-11-25**. It implements initialize, ping, tools/list, tools/call and notification acknowledgement. It returns JSON responses; GET/DELETE return 405 because server-initiated SSE and sessions are not offered. This is an explicitly supported protocol version, not a claim of compatibility with every future revision.

Use the proposal-only credential from `.workbench/agent.token`. Supply `Authorization: Bearer <token>` on every request, `Content-Type: application/json` and `Accept: application/json, text/event-stream`. Subsequent messages use `MCP-Protocol-Version: 2025-11-25`. The server binds to loopback by default and validates Host/Origin. The explicit [hosted profile](deploy-render.md) permits one configured HTTPS origin and adds username/password access for browsers; scoped bearer credentials remain the recommended interface for agent/MCP clients. OAuth discovery remains outside this PoC.

Initialize with:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"local-engineering-client","version":"1.0"}}}
```

Send `notifications/initialized`, then discover tools using `{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}`. Tool discovery publishes complete input schemas and read-only annotations. Tool responses contain text and structured content; domain failures set `isError=true`.

| Tool | Authority |
| --- | --- |
| `list_environments` | Read current observations |
| `inspect_environment` | Read desired/observed state and drift |
| `propose_environment` | Interpret a bounded request and create a proposal |
| `propose_intent` | Validate and compile a complete structured intent |
| `propose_reconciliation` | Create a reviewed recovery proposal |
| `list_repair_tasks` | Read public repository tasks |
| `list_repositories` | Discover managed repository identities and heads |
| `inspect_repository` | Read a managed repository head |
| `start_repair` | Execute bounded isolated tests and submit a patch for review |
| `inspect_repair` | Read trace, test evidence and patch |

There is no environment-apply, patch-approval, fault-injection, feedback-capture or promotion tool. Those operations require the operator token through the workbench API. A prompt, repository file or tool argument cannot promote an agent credential to operator authority.

For example, `tools/call` accepts `{"name":"inspect_repair","arguments":{"id":"<run-id>"}}`. `start_repair` requires `repositoryId`, `instruction`, `provider`, `maxAttempts` and `timeoutSeconds`; it accepts no shell command. Providers are `mutation-search`, `gemini` and `nebius`; model providers require configured credentials and permit at most three attempts. An available isolated runner is required for execution. Operators create source snapshots separately before agents can work on them.

The implementation follows the [pinned transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) and [tool](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) contracts. HTTP integration tests exercise discovery, structured proposals, notifications, forbidden tool names and role enforcement.

## Run the official SDK client

The reproducible client uses the official `@modelcontextprotocol/client` **2.4.0**, pinned in the development lockfile. It performs the real initialize/initialized handshake over HTTP and negotiates this server's 2025-11-25 protocol. Node 22+ is used in CI. The Spring application and deployment image do not require Node or this SDK at runtime.

Start the packaged workbench with Java 21 and a preloaded Docker runner:

```sh
docker build -f sandbox/java21/Dockerfile -t xcloud-java21-runner:1 .
./mvnw verify
PLATFORM_SANDBOX_IMAGE=xcloud-java21-runner:1 java -jar target/AI-Native-Self-Managing-X-Cloud-Platform-0.0.1-SNAPSHOT.jar
```

In another terminal, install the development tools and inspect the connection:

```sh
npm ci
npm run mcp:inspect
```

The default endpoint is `http://127.0.0.1:8080/mcp`. A loopback connection reads `.workbench/agent.token` automatically, respecting `PLATFORM_DATA_DIR`; no token needs to be printed or pasted into a command argument. Set `MCP_URL` for another endpoint and `PLATFORM_AGENT_TOKEN` privately for a remote connection. The client requires HTTPS for remote hosts and refuses redirects or forwarding a token to another endpoint. It never falls back to the operator token or browser password.

## Demonstrate repair and review

1. In **Repository agent**, create **Discount calculation**. This operator action creates an actual managed repository with immutable regression tests.
2. Run `npm run mcp:inspect`. The MCP `list_repositories` tool returns its ID and pinned head, so the agent needs no operator API credential to discover its target.
3. Start a bounded repair through the SDK:

```sh
npm run mcp:repair -- --repository-id YOUR_REPOSITORY_ID \
  --instruction "Fix the discount calculation. Preserve negative-input rejection and unchanged tests." \
  --provider mutation-search --attempts 8 --seconds 90
```

The CLI calls `start_repair`, polls `inspect_repair`, prints state transitions and saves the actual run and protocol metadata to `target/mcp-demo.json`. It stops at `AWAITING_REVIEW` and provides the UI URL and run ID. It offers no approval or commit action. An observation timeout does not cancel a queued server job; an operator can inspect or cancel it in the workbench.

4. Open **Repository agent**, select that run and inspect **FAIL → PASS**, the source diff, unchanged test results and tool trace. The repository head is unchanged. Click **Review & commit patch** and check the review confirmation before committing.
5. Inspect the same result through MCP after the operator action:

```sh
npm run mcp:inspect -- --run-id YOUR_RUN_ID
```

It now reports `APPROVED` and the committed revision. The MCP agent can observe the outcome; it cannot approve it. This demonstration uses deterministic mutation search, not a live-model repair score. Gemini/Nebius runs require configured private model access and at most three attempts. Use the [live evaluation runbook](scaling-and-activation.md) to collect separate inference/quality evidence.

## Connect an IDE or agent

Choose the file format that matches your client. Merge the supplied server entry into an existing configuration rather than replacing other settings. The templates contain placeholders, never credential values.

| Client | Template | Configuration and authentication |
| --- | --- | --- |
| Google Gemini CLI | [gemini-settings.json](../examples/mcp/gemini-settings.json) | Merge `mcpServers` into `.gemini/settings.json`; provide `PLATFORM_AGENT_TOKEN` privately in the CLI's environment. Uses `httpUrl` for Streamable HTTP. |
| VS Code Copilot harness | [copilot-mcp.json](../examples/mcp/copilot-mcp.json) | Merge into Copilot's `mcp-config.json` (default `~/.copilot/mcp-config.json`), or workspace `.mcp.json` when that destination is enabled. Its header uses `${PLATFORM_AGENT_TOKEN}` from the agent host's environment. |
| VS Code Local harness | [vscode-local-mcp.json](../examples/mcp/vscode-local-mcp.json) | Merge into `.vscode/mcp.json`. Prompts for the proposal-only token with `password: true`. Input-based servers are not forwarded to the Copilot harness. |

For the hosted application, replace the template URL with `https://x-cloud-workbench.onrender.com/mcp` and supply the dedicated hosted agent token through private client configuration. The browser's Basic username/password is a separate operator access path. OAuth discovery is not implemented by this PoC. Configured clients must support static bearer credentials and the negotiated protocol revision.

Ask the agent to discover this server's tools, then use the [bounded repair demonstration prompt](../examples/mcp/review-demo.md). Keep normal client confirmation enabled. No client-side auto-approval setting can grant the server an approval tool.

The formats follow the [Gemini CLI MCP guide](https://geminicli.com/docs/tools/mcp-server/), [Gemini settings expansion](https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/config/settings.ts) and [VS Code configuration reference](https://code.visualstudio.com/docs/agents/reference/mcp-configuration). Actual IDE/CLI sign-in and interactive sessions require the user's client credentials; automated evidence uses the official SDK directly.

## Interoperability evidence

```sh
npm run test:mcp
MCP_REQUIRE_DOCKER=true npm run test:mcp
npm run test:e2e -- --grep "MCP agent repair"
```

The SDK suite boots the packaged Spring application with temporary private test credentials. It verifies the real handshake, ten discovered tools, structured proposals, domain errors, invalid credentials, foreign origins, extra arguments, forbidden approval tools and the CLI. When Docker is available, it runs a real failing-baseline/passing-verification repair and proves the repository head remains unchanged and the agent cannot approve. `MCP_REQUIRE_DOCKER=true` makes unavailable execution fail CI; it does not simulate a repair.

The browser scenario submits the repair through that SDK using the agent scope, then requires the operator's explicit UI review checkbox and commit action. CI retains `target/mcp-sdk-evidence.json` and `target/mcp-review-evidence.json` alongside existing execution evidence. The Node suite skips the Docker phase locally when Docker is unavailable; a partial report is not full execution evidence.
