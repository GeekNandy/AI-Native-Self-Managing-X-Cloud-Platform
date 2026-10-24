# Hosted demonstration

## Current service

The Free Frankfurt service is [x-cloud-workbench.onrender.com](https://x-cloud-workbench.onrender.com), deployed from **feature**. Its [private Environment page](https://dashboard.render.com/web/srv-dbcrvv4s728c73826th0/env) holds the dedicated `DEMO_USERNAME` and `DEMO_PASSWORD`; no credential value is published here. Configure one available model privately and use **Save and deploy** to activate it. Gemini and Nebius adapters are optional; provider selection should follow measured repair quality and available access. Until configured, the app labels its fixture/reference behavior.

The [recorded hosted verification](evidence/render-deployment.json) identifies the deployed commit, authentication checks and actual simulator workflow. Render currently uses its default TCP health check; the report separately verifies HTTP `/actuator/health`. The HTTP check path can be configured on the service's Settings page.

Deploy the repository's **feature** branch as one Docker web service. The root Dockerfile compiles Java 21, installs Git in a separate non-root runtime, binds to `0.0.0.0:$PORT`, and requires username/password access before serving the UI, configuration, API, metrics or MCP. `/actuator/health` is public liveness and contains no details.

Use the **Free** plan for the initial demonstration, select **Docker**, keep the repository root as the build context, and deploy the **feature** branch. Set the HTTP health-check path to `/actuator/health` so readiness does not depend on an authenticated UI request.

Configure runtime environment variables privately in Render:

| Variable | Value |
| --- | --- |
| `DEMO_USERNAME` | Your chosen demonstration username |
| `DEMO_PASSWORD` | A unique random password, at least 16 characters |
| `PUBLIC_ORIGIN` | Exact HTTPS app origin; defaults to Render's documented `RENDER_EXTERNAL_URL` |
| `DEMO_AUTH_REQUIRED` | `true` (also set by the image) |
| `PLATFORM_AI_PROVIDER` | `gemini`, `nebius`, or `fixture` for labelled offline interpretation |
| `GEMINI_API_KEY` / `GEMINI_MODEL` | Private Gemini credential and an accessible model identifier, when selected |
| `NEBIUS_API_KEY` / `NEBIUS_MODEL` | Private Token Factory credential and an accessible model identifier, when selected |
| `PLATFORM_EXECUTION_MODE` | `remote` for the hosted pull-worker queue |
| `DATABASE_URL` | Private internal PostgreSQL URL; provisioned database `x-cloud-workbench-state` |
| `GITHUB_WORKBENCH_TOKEN` | Optional fine-grained token for private imports and reviewed draft PR publication |
| `GITHUB_PUBLISH_REPOSITORY` | Fixed destination `GeekNandy/AI-Native-Self-Managing-X-Cloud-Platform` |
| `GITHUB_PUBLISH_BRANCH` | `feature`; a moved base requires a fresh import and verification |
| `PLATFORM_RUNTIME` | `sandbox` for the hosted environment simulator |
| `DEMO_EPHEMERAL` | `true` for the baseline demo; set `false` only after persistent storage is configured |
| `PLATFORM_DATA_DIR` | `/app/.workbench`, or a writable persistent-disk mount when configured |

The browser uses its native HTTPS username/password prompt. The UI then opens without the local operator-token dialog. Scoped bearer credentials still support separate agent/MCP clients; an agent cannot approve, commit, promote or mutate operator-only resources. Foreign Host and Origin headers remain rejected. Missing/partial public authentication configuration stops startup.

Provider endpoints are fixed to their official HTTPS APIs and do not follow redirects. Calls have bounded input/output, complete-response deadlines and no execution or approval authority. Model proposals still pass source/hash validation, isolated unchanged tests and exact-digest review. Repair receipts record requested/reported model identity and actual reported usage; missing usage is unknown, and cost remains unpriced. Live quality must be evaluated with the project's own corpus rather than inferred from another deployment's results.

## Runtime and persistence

The Render web coordinator uses authenticated Docker workers outside Render. Start a bounded **Hosted repair worker** Actions window on `feature` (rerun its latest job until the workflow is on the default branch). OIDC credentials authorize only the worker protocol. The deployed app has verified a pinned GitHub/Maven import, failing baseline, passing repair and idempotent approval. See [remote execution](remote-execution.md) and the [hosted receipt](evidence/hosted-remote-repair.json). Uploaded Java never runs in the web server.

The Free service filesystem remains ephemeral. The coordinator supports PostgreSQL-backed state and integrity-checked Git archives, including recovery after losing local checkouts. The provisioned Free Frankfurt database is **not yet connected**: copy its internal connection URL privately into `DATABASE_URL`, then set `DEMO_EPHEMERAL=false`. Its free plan expires on **2026-11-21** and has no managed backups; configure lasting storage before relying on durable hosted data. Embedded H2 remains the default for local development.

Branch updates have not automatically triggered this service during verification despite its auto-deploy setting. Inspect the current deploy revision and use a manual deploy when needed; do not treat a GitHub push as deployment evidence.

## Verify after deployment

1. Confirm the deploy reaches `live` and `/actuator/health` returns 200.
2. Confirm a fresh request to `/`, `/api/config` and `/actuator/metrics` gets a 401 Basic challenge.
3. Verify correct credentials open the UI and API; incorrect credentials and foreign origins are rejected.
4. Compile a new request in live mode, inspect all policy results, approve a simulator plan and exercise reviewed drift recovery.
5. Run interpreter evaluation and retain its actual provider/model and outcomes. Do not substitute fixture replay scores for live results.
6. Start an authenticated worker window, import the pinned sample and verify baseline → patch → test → digest-bound approval.
7. Run paired repair evaluation only with configured providers. Inspect regression gates and actual usage before promotion.
8. If PostgreSQL is configured, verify repository/job recovery across a hosted restart. If a GitHub write credential is configured, verify one reviewed draft PR and its idempotent retry.

Never put model keys, login passwords, bearer tokens or deploy hooks in this repository, build arguments, images, public docs or logs. Configure secrets at runtime. Use a new password for this project rather than reusing another application's login.
