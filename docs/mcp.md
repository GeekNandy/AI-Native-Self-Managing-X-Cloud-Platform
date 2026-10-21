# MCP connection

The local server exposes `/mcp` using stateless Streamable HTTP, pinned to **2025-11-25**. It implements initialize, ping, tools/list, tools/call and notification acknowledgement. It returns JSON responses; GET/DELETE return 405 because server-initiated SSE and sessions are not offered. This is an explicitly supported protocol version, not a claim of compatibility with every future revision.

Use the proposal-only credential from `.workbench/agent.token`. Supply `Authorization: Bearer <token>` on every request, `Content-Type: application/json` and `Accept: application/json, text/event-stream`. Subsequent messages use `MCP-Protocol-Version: 2025-11-25`. The server binds to loopback and validates Host/Origin. Bearer tokens implement local access; OAuth discovery and remote public hosting are outside this PoC.

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
| `inspect_repository` | Read a managed repository head |
| `start_repair` | Execute bounded isolated tests and submit a patch for review |
| `inspect_repair` | Read trace, test evidence and patch |

There is no environment-apply, patch-approval, fault-injection, feedback-capture or promotion tool. Those operations require the operator token through the workbench API. A prompt, repository file or tool argument cannot promote an agent credential to operator authority.

For example, `tools/call` accepts `{"name":"inspect_repair","arguments":{"id":"<run-id>"}}`. `start_repair` requires `repositoryId`, `instruction`, `provider`, `maxAttempts` and `timeoutSeconds`; it accepts no shell command. Operators create source snapshots separately before agents can work on them.

The implementation follows the [pinned transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) and [tool](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) contracts. HTTP integration tests exercise discovery, structured proposals, notifications, forbidden tool names and role enforcement.
